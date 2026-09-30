---------------------------- MODULE CountBarrier ----------------------------
(***************************************************************************)
(* The round protocol of DistributedBarrierWithCount                       *)
(* (etcd-recipes-core/.../barrier/DistributedBarrierWithCount.kt).         *)
(*                                                                         *)
(* A party calling waitOnBarrier():                                        *)
(*   1. joins the round in progress, or starts one: a transaction creates  *)
(*      /ready if absent, and the round is /ready's create revision;       *)
(*   2. registers a lease-bound waiting key under waiting/<round>/;        *)
(*   3. leaves, released, once /ready no longer names its round (it was    *)
(*      deleted, and perhaps a later round began); or, seeing at least N   *)
(*      waiting keys in its round, deletes /ready guarded on the round's   *)
(*      create revision (retried; a failure leaves it parked) and then     *)
(*      leaves, released;                                                  *)
(*   4. or leaves unreleased on a timeout.                                 *)
(* Leaving deletes its waiting key. A waiting key's lease can expire while *)
(* the party is parked; its healer puts the key back. Parties loop: each   *)
(* calls waitOnBarrier() Waits times.                                      *)
(*                                                                         *)
(* A watch on the barrier prefix (DELETE of /ready, PUT under its round)   *)
(* plus the rechecks after watch recovery deliver every change the         *)
(* protocol reacts to, so a parked party's reactions are modeled as        *)
(* actions enabled by the etcd state itself.                               *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS
  Parties,        \* the parties meeting at one barrier path
  N,              \* memberCount
  Waits,          \* how many times each party calls waitOnBarrier()
  MaxExpiries,    \* how many times each party's waiting-key lease may expire
  AllowTimeouts   \* whether a parked party may give up

ASSUME N \in Nat \ {0} /\ Waits \in Nat /\ MaxExpiries \in Nat /\ AllowTimeouts \in BOOLEAN

\* Each wait advances the revision at most 4 times (join, register, release, leave),
\* plus 2 per lease expiry (expire, heal), so every revision stays within Revs.
Revs == 1 .. (Cardinality(Parties) * Waits * (4 + 2 * MaxExpiries))

VARIABLES
  rev,        \* etcd's store revision
  ready,      \* /ready's create revision, the round in progress; 0 when there is none
  waiting,    \* the waiting keys in etcd, as <<round, party>>
  pc,         \* each party's step: "idle", "joined", "parked", "releasing", "leaving"
  round,      \* each party's round; 0 when it isn't waiting
  waitsLeft,  \* the waits each party has yet to start
  expiries,   \* the lease expiries each party has had
  tripped,    \* history: the rounds whose /ready was deleted
  peak,       \* history: the most waiting keys each round ever had at once
  released    \* history: <<party, round>> for each release

vars == <<rev, ready, waiting, pc, round, waitsLeft, expiries, tripped, peak, released>>

Count(r, keys) == Cardinality({k \in keys : k[1] = r})

TypeOK ==
  /\ rev \in Nat
  /\ ready \in {0} \cup Revs
  /\ waiting \subseteq Revs \X Parties
  /\ pc \in [Parties -> {"idle", "joined", "parked", "releasing", "leaving"}]
  /\ round \in [Parties -> {0} \cup Revs]
  /\ waitsLeft \in [Parties -> 0 .. Waits]
  /\ expiries \in [Parties -> 0 .. MaxExpiries]
  /\ tripped \subseteq Revs
  /\ peak \in [Revs -> Nat]
  /\ released \subseteq Parties \X Revs

Init ==
  /\ rev = 0
  /\ ready = 0
  /\ waiting = {}
  /\ pc = [p \in Parties |-> "idle"]
  /\ round = [p \in Parties |-> 0]
  /\ waitsLeft = [p \in Parties |-> Waits]
  /\ expiries = [p \in Parties |-> 0]
  /\ tripped = {}
  /\ peak = [r \in Revs |-> 0]
  /\ released = {}

\* Puts p's waiting key in its round, tracking the round's peak
PutKey(p) ==
  LET r == round[p]
      keys == waiting \cup {<<r, p>>}
  IN /\ waiting' = keys
     /\ rev' = rev + 1
     /\ peak' = [peak EXCEPT ![r] = IF Count(r, keys) > @ THEN Count(r, keys) ELSE @]

\* p's wait ends: its waiting key is deleted (if present) and it can wait again
Leave(p, isReleased) ==
  LET key == <<round[p], p>>
  IN /\ IF key \in waiting
          THEN /\ waiting' = waiting \ {key}
               /\ rev' = rev + 1
          ELSE UNCHANGED <<waiting, rev>>
     /\ released' = IF isReleased THEN released \cup {<<p, round[p]>>} ELSE released
     /\ pc' = [pc EXCEPT ![p] = "idle"]
     /\ round' = [round EXCEPT ![p] = 0]

\* 1. joinRound(): If(/ready absent) Then(put /ready) Else(get /ready), in one transaction
Join(p) ==
  /\ pc[p] = "idle"
  /\ waitsLeft[p] > 0
  /\ IF ready = 0
       THEN /\ ready' = rev + 1
            /\ rev' = rev + 1
            /\ round' = [round EXCEPT ![p] = rev + 1]
       ELSE /\ round' = [round EXCEPT ![p] = ready]
            /\ UNCHANGED <<ready, rev>>
  /\ pc' = [pc EXCEPT ![p] = "joined"]
  /\ waitsLeft' = [waitsLeft EXCEPT ![p] = @ - 1]
  /\ UNCHANGED <<waiting, expiries, tripped, peak, released>>

\* 2. The waiting key's establish: a create under waiting/<round>/
Register(p) ==
  /\ pc[p] = "joined"
  /\ PutKey(p)
  /\ pc' = [pc EXCEPT ![p] = "parked"]
  /\ UNCHANGED <<ready, round, waitsLeft, expiries, tripped, released>>

\* 3a. checkWaiterCount(): /ready no longer names this round, so it tripped
Passed(p) ==
  /\ pc[p] = "parked"
  /\ ready # round[p]
  /\ Leave(p, TRUE)
  /\ UNCHANGED <<ready, waitsLeft, expiries, tripped, peak>>

\* 3b. checkWaiterCount(): the round is full, so release it before leaving
Full(p) ==
  /\ pc[p] = "parked"
  /\ ready = round[p]
  /\ Count(round[p], waiting) >= N
  /\ pc' = [pc EXCEPT ![p] = "releasing"]
  /\ UNCHANGED <<rev, ready, waiting, round, waitsLeft, expiries, tripped, peak, released>>

\* 3c. releaseRound(): delete /ready guarded on the round's create revision. A failed
\*     compare means the round is already over; either way the party goes on to leave.
ReleaseRound(p) ==
  /\ pc[p] = "releasing"
  /\ IF ready = round[p]
       THEN /\ ready' = 0
            /\ tripped' = tripped \cup {round[p]}
            /\ rev' = rev + 1
       ELSE UNCHANGED <<ready, tripped, rev>>
  /\ pc' = [pc EXCEPT ![p] = "leaving"]
  /\ UNCHANGED <<waiting, round, waitsLeft, expiries, peak, released>>

\* 3d. Only then does the party leave, released (closeKeepAlive(), a separate RPC)
Finish(p) ==
  /\ pc[p] = "leaving"
  /\ Leave(p, TRUE)
  /\ UNCHANGED <<ready, waitsLeft, expiries, tripped, peak>>

\* 3e. releaseRound()'s retries fail (an etcd blip): recorded, and the party stays parked
ReleaseFails(p) ==
  /\ pc[p] = "releasing"
  /\ pc' = [pc EXCEPT ![p] = "parked"]
  /\ UNCHANGED <<rev, ready, waiting, round, waitsLeft, expiries, tripped, peak, released>>

\* 4. A parked party times out
Timeout(p) ==
  /\ AllowTimeouts
  /\ pc[p] = "parked"
  /\ Leave(p, FALSE)
  /\ UNCHANGED <<ready, waitsLeft, expiries, tripped, peak>>

\* The waiting key's lease expires while its party waits: etcd deletes the key
Expire(p) ==
  /\ pc[p] \in {"parked", "releasing", "leaving"}
  /\ <<round[p], p>> \in waiting
  /\ expiries[p] < MaxExpiries
  /\ waiting' = waiting \ {<<round[p], p>>}
  /\ rev' = rev + 1
  /\ expiries' = [expiries EXCEPT ![p] = @ + 1]
  /\ UNCHANGED <<ready, pc, round, waitsLeft, tripped, peak, released>>

\* The healer re-grants a lease and puts the key back
Heal(p) ==
  /\ pc[p] \in {"parked", "releasing", "leaving"}
  /\ <<round[p], p>> \notin waiting
  /\ PutKey(p)
  /\ UNCHANGED <<ready, pc, round, waitsLeft, expiries, tripped, released>>

PartyStep(p) ==
  \/ Join(p) \/ Register(p) \/ Passed(p) \/ Full(p)
  \/ ReleaseRound(p) \/ Finish(p) \/ ReleaseFails(p) \/ Timeout(p)
  \/ Expire(p) \/ Heal(p)

\* A party left alone in a round waits for good (as the recipe does without a timeout), so
\* the models don't check for deadlock.
Next == \E p \in Parties : PartyStep(p)

Fairness ==
  \A p \in Parties :
    /\ WF_vars(Register(p))
    /\ WF_vars(Passed(p))
    /\ WF_vars(Full(p))
    /\ SF_vars(ReleaseRound(p))  \* retries succeed once etcd recovers
    /\ WF_vars(Finish(p))
    /\ WF_vars(Heal(p))

Spec == Init /\ [][Next]_vars /\ Fairness

---------------------------------------------------------------------------
(* Safety *)

\* A round trips only after N parties were waiting in it at once: leftovers of an
\* earlier round can't fill a later one (review issue 41).
NoEarlyTrip == \A r \in tripped : peak[r] >= N

\* A party leaves released only after its round's /ready was deleted: nobody leaves
\* a round that everyone else still sees standing (review issue 40).
ReleasedAfterTrip == \A pr \in released : pr[2] \in tripped

(* Liveness, without timeouts *)

\* Once a round is full, it trips
FullRoundsTrip == \A r \in Revs : (Count(r, waiting) >= N) ~> (r \in tripped)
=============================================================================
