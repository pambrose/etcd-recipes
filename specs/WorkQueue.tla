------------------------------ MODULE WorkQueue ------------------------------
(***************************************************************************)
(* The claim protocol of DistributedWorkQueue                              *)
(* (etcd-recipes-core/.../queue/DistributedWorkQueue.kt), an at-least-once *)
(* work queue. Its keys, under the queue path:                             *)
(*   items/<id>     a queued item; requeue() puts it back under the same   *)
(*                  key, so it keeps its place                             *)
(*   claimed/<id>   the payload of a claimed item                          *)
(*   claims/<id>    the claim marker, <clientId>:<nonce> with a nonce      *)
(*                  unique to the claim attempt, bound to the instance's   *)
(*                  consumer lease. Its create revision is the claim's     *)
(*                  token (WorkItem.claimRevision).                        *)
(*   attempts/<id>  how many times the item was delivered                  *)
(*   dlq/<id>       a dead letter                                          *)
(*                                                                         *)
(* A consumer's receive() runs claimHead(), which:                         *)
(*   1. reads the head of items/;                                          *)
(*   2. reads attempts/<id>. At maxDeliveries it dead-letters the head (a  *)
(*      transaction guarded on the head's mod revision) and starts over;   *)
(*      otherwise it reads the consumer lease's current id;                *)
(*   3. claims the head: one transaction, guarded on the head's mod        *)
(*      revision, deletes items/<id> and puts claimed/<id>, the leased     *)
(*      claims/<id>, and attempts/<id> + 1. A lost race or a lapsed lease  *)
(*      starts over;                                                       *)
(*   4. if the claim's response is lost (the commit is ambiguous), calls   *)
(*      reconcileClaim(), which re-reads claims/<id>. The claim is its own *)
(*      if the marker holds this attempt's value on the lease it claimed   *)
(*      with. If the re-read fails too, it records the attempt in          *)
(*      unresolvedClaims, keyed by its marker, and receive() throws.       *)
(* The consumer then holds a WorkItem and later ack()s or requeue()s it.   *)
(* If a suspending receive was cancelled while it claimed, the caller      *)
(* never sees the WorkItem, and claimUnclaimingOnCancel() unclaim()s it    *)
(* instead. ack, requeue, and unclaim are each a transaction guarded on    *)
(* the marker still holding the WorkItem's value and create revision.      *)
(*                                                                         *)
(* The consumer lease is the visibility timeout. When it lapses, etcd      *)
(* deletes its claim markers, and SelfHealingKeepAlive grants a new lease  *)
(* for later claims. Each consumer sweeps for orphans (reclaimOrphans(),   *)
(* run periodically and by a receive that finds the queue empty). It lists *)
(* claimed/, then claims/. Each orphan (a claimed/ key with no marker)     *)
(* goes back to items/, or to dlq/ once its attempts reach maxDeliveries,  *)
(* in a transaction guarded on the marker's absence and on claimed/<id>'s  *)
(* mod revision. Two consumers can sweep at once. Each periodic sweep      *)
(* then runs releaseUnresolvedClaims(): an unresolved claim whose marker   *)
(* still holds that attempt's value is given back, as unclaim() does.      *)
(*                                                                         *)
(* Each RPC is its own action, so other processes can interleave between   *)
(* any two of them. The one exception is a sweep's two listings (see       *)
(* SweepList). A consumer is one receive/ack thread and holds any number   *)
(* of WorkItems. Its instance is itself, or, with SharedInstance, one      *)
(* DistributedWorkQueue that all the threads share, with one clientId, one *)
(* lease, and one unresolvedClaims.                                        *)
(*                                                                         *)
(* etcd's revision is kept per item. Each compare in the protocol matches  *)
(* one of an item's keys against a revision read from, or written by, that *)
(* item's keys, so only the order of writes to one item matters. rev[i]    *)
(* counts them, and a token is unique per item. A nonce is named by the    *)
(* claim it committed (see Nonces).                                        *)
(*                                                                         *)
(* Not modeled: delayed items (promoteMatured), enqueueAll, the admin      *)
(* calls requeueDeadLetter and purgeDeadLetter, and close(). close()       *)
(* revokes the lease and stops the consumer, which a lease lapse followed  *)
(* by an idle consumer already covers. Lost responses are modeled only on  *)
(* the claim and its re-read. On the other RPCs they are guarded writes,   *)
(* and the caller just sees an exception.                                  *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS
  Consumers,       \* the consumer threads, each running receive() then ack()/requeue()
  NumItems,        \* how many items are enqueued: ids 1..NumItems, in key order
  MaxAttempts,     \* WorkQueueConfig.maxDeliveries
  MaxExpiries,     \* how many times a current lease may lapse or be re-granted early
  MaxLost,         \* how many RPC responses may be lost, in all
  MaxUnclaims,     \* how many cancelled suspending receives may unclaim(), in all
  SharedInstance,  \* whether the consumers are threads of one DistributedWorkQueue
  None             \* no claim marker

ASSUME
  /\ Consumers # {}
  /\ NumItems \in Nat \ {0}
  /\ MaxAttempts \in Nat \ {0}
  /\ MaxExpiries \in Nat /\ MaxLost \in Nat /\ MaxUnclaims \in Nat
  /\ SharedInstance \in BOOLEAN

Items == 1 .. NumItems

\* The DistributedWorkQueue instance a consumer thread uses: its clientId, consumer lease,
\* and unresolved claims. With SharedInstance, all the threads share one instance.
Inst(c) == IF SharedInstance THEN CHOOSE x \in Consumers : TRUE ELSE c
Instances == {Inst(c) : c \in Consumers}

\* Consumers are interchangeable when each has its own instance (WorkQueue.cfg)
Symm == Permutations(Consumers)

\* A lease id is <<instance, generation>>: each heal or early re-grant is a new lease
LeaseIds == Instances \X (0 .. MaxExpiries)

\* Each write to an item's keys advances its revision. An item has one enqueue and at
\* most one dead-lettering. Each of its deliveries (at most MaxAttempts, plus one per
\* unclaim or release) is one claim plus at most one ack, requeue, unclaim, release, or
\* reclaim. A release needs a failed re-read, which is a lost response. A lapse's deletes
\* aren't counted, because no compare reads a deleted key's revision. So every revision
\* stays within Revs.
MaxRev == 2 + 2 * (MaxAttempts + MaxUnclaims + MaxLost)
Revs == 1 .. MaxRev

\* A claim attempt's nonce. The code draws it at random, so a marker's value equals an
\* attempt's <clientId>:<nonce> exactly when that attempt wrote the marker. The model
\* names a nonce by the claim it committed: <<thread, token>>. A token is unique per item,
\* and every comparison is on one item's key. An attempt whose claim never commits has a
\* nonce that no marker holds: None.
Nonces == Consumers \X Revs

\* A claim marker. Its value is <<client, nonce>>. holder is history, not etcd state: the
\* consumer whose claim created it.
Claims ==
  [client : Instances, nonce : Nonces, lease : LeaseIds, token : Revs, holder : Consumers]

\* WorkItem(id, value, attempt, claimRevision, claimMarker)
WorkItems == [item : Items, token : Revs, attempt : Nat \ {0}, nonce : Nonces]

\* An entry of unresolvedClaims, which is keyed by the attempt's marker: the item, attempt
\* number, and nonce of a claim whose transaction got no answer and whose re-read failed
Unresolved == [item : Items, attempt : Nat \ {0}, nonce : {None} \cup Nonces]

\* A consumer's receive in progress: the head it read (item, mod revision), the
\* deliveries it read, the lease id it claims with, and its attempt's nonce (None until
\* its claim commits). While it unclaims, rev is the claim's token.
Receives ==
  [pc : {"idle", "gotHead", "exhausted", "claiming", "reconciling", "unclaiming"},
   item : {0} \cup Items, rev : {0} \cup Revs, delivered : Nat, lease : LeaseIds,
   nonce : {None} \cup Nonces]

\* A sweep in progress: the orphans it has yet to handle, and what it read about the
\* current one. While it releases an unresolved claim, todo is its item, rev the marker's
\* create revision, and att and nonce the entry's.
Sweeps ==
  [pc : {"idle", "scanning", "got", "counted", "releasing"},
   todo : SUBSET Items, rev : {0} \cup Revs, att : Nat, nonce : {None} \cup Nonces]

VARIABLES
  \* etcd
  rev,         \* each item's revision: the last write to its keys
  items,       \* items/<id>'s mod revision; 0 when absent
  claimed,     \* claimed/<id>'s mod revision; 0 when absent
  claims,      \* claims/<id>, a Claims record; None when absent
  attempts,    \* attempts/<id>; 0 when absent
  dlq,         \* the ids under dlq/
  leases,      \* the lease ids etcd holds
  \* each instance's SelfHealingKeepAlive
  leaseGen,    \* the generation of currentLeaseId
  expiries,    \* the lapses and early re-grants of current leases, in all
  \* each instance
  unresolved,  \* unresolvedClaims: its entries, one per claim attempt
  \* each consumer
  rcv,         \* its receive in progress
  held,        \* the WorkItems it holds
  sw,          \* its sweep in progress
  \* faults
  lost,        \* the responses lost so far
  unclaims,    \* the cancelled receives so far
  \* the producer, and history
  enqueued,    \* how many items were enqueued
  acks,        \* each successful ack: <<item, whether it was under the acker's claim>>
  deliveries   \* each item's committed claims, less those given back

etcd == <<rev, items, claimed, claims, attempts, dlq, leases>>
keepAlive == <<leaseGen, expiries>>
faults == <<lost, unclaims>>
instance == <<keepAlive, unresolved>>
vars == <<etcd, instance, rcv, held, sw, faults, enqueued, acks, deliveries>>

Min(S) == CHOOSE x \in S : \A y \in S : x <= y

Enqueued(i) == i <= enqueued
Queued(i) == items[i] # 0
IsClaimed(i) == claimed[i] # 0
Acked(i) == \E a \in acks : a[1] = i
Dead(i) == i \in dlq

\* The revision a write to item i's keys commits at, and that write's advance
NewRev(i) == rev[i] + 1
Bump(i) == rev' = [rev EXCEPT ![i] = @ + 1]

IdleReceive(c) ==
  [pc |-> "idle", item |-> 0, rev |-> 0, delivered |-> 0, lease |-> <<Inst(c), 0>>,
   nonce |-> None]
IdleSweep == [pc |-> "idle", todo |-> {}, rev |-> 0, att |-> 0, nonce |-> None]

\* The marker m holds the value <clientId>:<nonce>
HoldsValue(m, x, n) == m # None /\ m.client = x /\ m.nonce = n

\* The counters are typed as Nat: NoDeliveryPastMaxAttempts and AttemptsCountDeliveries
\* bound them
TypeOK ==
  /\ rev \in [Items -> 0 .. MaxRev]
  /\ items \in [Items -> {0} \cup Revs]
  /\ claimed \in [Items -> {0} \cup Revs]
  /\ claims \in [Items -> {None} \cup Claims]
  /\ attempts \in [Items -> Nat]
  /\ dlq \subseteq Items
  /\ leases \subseteq LeaseIds
  /\ leaseGen \in [Instances -> 0 .. MaxExpiries]
  /\ expiries \in 0 .. MaxExpiries
  /\ unresolved \in [Instances -> SUBSET Unresolved]
  /\ rcv \in [Consumers -> Receives]
  /\ held \in [Consumers -> SUBSET WorkItems]
  /\ sw \in [Consumers -> Sweeps]
  /\ lost \in 0 .. MaxLost
  /\ unclaims \in 0 .. MaxUnclaims
  /\ enqueued \in 0 .. NumItems
  /\ acks \subseteq Items \X BOOLEAN
  /\ deliveries \in [Items -> Nat]

Init ==
  /\ rev = [i \in Items |-> 0]
  /\ items = [i \in Items |-> 0]
  /\ claimed = [i \in Items |-> 0]
  /\ claims = [i \in Items |-> None]
  /\ attempts = [i \in Items |-> 0]
  /\ dlq = {}
  /\ leases = {<<x, 0>> : x \in Instances}
  /\ leaseGen = [x \in Instances |-> 0]
  /\ expiries = 0
  /\ unresolved = [x \in Instances |-> {}]
  /\ rcv = [c \in Consumers |-> IdleReceive(c)]
  /\ held = [c \in Consumers |-> {}]
  /\ sw = [c \in Consumers |-> IdleSweep]
  /\ lost = 0
  /\ unclaims = 0
  /\ enqueued = 0
  /\ acks = {}
  /\ deliveries = [i \in Items |-> 0]

---------------------------------------------------------------------------
(* The producer *)

\* enqueue(): createUniqueKey puts items/<millis>-<suffix>, after every queued key
Enqueue ==
  /\ enqueued < NumItems
  /\ items' = [items EXCEPT ![enqueued + 1] = NewRev(enqueued + 1)]
  /\ Bump(enqueued + 1)
  /\ enqueued' = enqueued + 1
  /\ UNCHANGED <<claimed, claims, attempts, dlq, leases, instance, rcv, held, sw, faults,
                 acks, deliveries>>

---------------------------------------------------------------------------
(* A consumer's receive(): claimHead() and reconcileClaim() *)

\* 1. claimHead(): getFirstChild(items/) reads the head's key, value, and mod revision.
\*    On an empty queue, receive() sweeps and parks on a watch anchored at the revision
\*    where it saw items/ empty (review issue 49), so only a read that finds an item
\*    matters.
ReadHead(c) ==
  /\ rcv[c].pc = "idle"
  /\ \E i \in Items : Queued(i)
  /\ LET h == Min({i \in Items : Queued(i)})
     IN rcv' = [rcv EXCEPT ![c] = [@ EXCEPT !.pc = "gotHead", !.item = h,
                                                !.rev = items[h]]]
  /\ UNCHANGED <<etcd, instance, held, sw, faults, enqueued, acks, deliveries>>

\* 2. claimHead(): getValue(attempts/<id>) reads the deliveries so far. At maxDeliveries,
\*    the head is dead-lettered instead (review issue 16). Otherwise consumerLeaseId()
\*    reads the lease's current id. That read is local, and it names a lapsed lease until
\*    the healer re-grants (review issue 17).
ReadAttempts(c) ==
  LET r == rcv[c]
      n == attempts[r.item]
  IN /\ r.pc = "gotHead"
     /\ rcv' = [rcv EXCEPT ![c] =
                  IF n >= MaxAttempts
                    THEN [r EXCEPT !.pc = "exhausted", !.delivered = n]
                    ELSE [r EXCEPT !.pc = "claiming", !.delivered = n,
                                   !.lease = <<Inst(c), leaseGen[Inst(c)]>>]]
     /\ UNCHANGED <<etcd, instance, held, sw, faults, enqueued, acks, deliveries>>

\* 2a. deadLetterExhausted(): If(items/<id> mod = the head's) Then(delete items/<id>,
\*     put dlq/<id>). Whether or not it wins, claimHead() starts over.
DeadLetter(c) ==
  LET r == rcv[c]
  IN /\ r.pc = "exhausted"
     /\ IF items[r.item] = r.rev
          THEN /\ items' = [items EXCEPT ![r.item] = 0]
               /\ dlq' = dlq \cup {r.item}
               /\ Bump(r.item)
          ELSE UNCHANGED <<items, dlq, rev>>
     /\ rcv' = [rcv EXCEPT ![c] = IdleReceive(c)]
     /\ UNCHANGED <<claimed, claims, attempts, leases, instance, held, sw, faults,
                    enqueued, acks, deliveries>>

\* The claim transaction commits: its compare holds and its lease is alive. A failed
\* compare skips the puts, so only a passing one can fail on a lapsed lease.
CanClaim(c) == items[rcv[c].item] = rcv[c].rev /\ rcv[c].lease \in leases

\* The nonce c's claim attempt commits with: the marker it writes is <<c, token>>
NewNonce(c) == <<c, NewRev(rcv[c].item)>>

\* The claim transaction's writes: delete items/<id>; put claimed/<id>, the leased
\* claims/<id> (value <clientId>:<nonce>), and attempts/<id> = delivered + 1
Grant(c) ==
  LET r == rcv[c]
      i == r.item
  IN /\ items' = [items EXCEPT ![i] = 0]
     /\ claimed' = [claimed EXCEPT ![i] = NewRev(i)]
     /\ claims' = [claims EXCEPT ![i] = [client |-> Inst(c), nonce |-> NewNonce(c),
                                          lease |-> r.lease, token |-> NewRev(i),
                                          holder |-> c]]
     /\ attempts' = [attempts EXCEPT ![i] = r.delivered + 1]
     /\ Bump(i)
     /\ deliveries' = [deliveries EXCEPT ![i] = @ + 1]

\* receive() returns the WorkItem w to its caller. If a suspending receive
\* (awaitReceive) was cancelled while it claimed, claimUnclaimingOnCancel() instead
\* goes on to unclaim w, and the caller never sees it (review issue 22).
Handover(c, w) ==
  \/ /\ held' = [held EXCEPT ![c] = @ \cup {w}]
     /\ rcv' = [rcv EXCEPT ![c] = IdleReceive(c)]
     /\ UNCHANGED unclaims
  \/ /\ unclaims < MaxUnclaims
     /\ unclaims' = unclaims + 1
     /\ rcv' = [rcv EXCEPT ![c] = [@ EXCEPT !.pc = "unclaiming", !.item = w.item,
                                              !.rev = w.token, !.nonce = w.nonce,
                                              !.delivered = w.attempt - 1]]
     /\ UNCHANGED held

\* 3. claimHead()'s claim transaction, answered: If(items/<id> mod = the head's)
\*    Then(Grant). On success, the WorkItem's token is the transaction's revision, which
\*    is the marker's create revision (review issue 90). A lost race (the compare
\*    failed) or a lapsed lease (LeaseNotFound, review issue 17) starts over.
ClaimTxn(c) ==
  LET r == rcv[c]
  IN /\ r.pc = "claiming"
     /\ IF CanClaim(c)
          THEN /\ Grant(c)
               /\ Handover(c, [item |-> r.item, token |-> NewRev(r.item),
                               attempt |-> r.delivered + 1, nonce |-> NewNonce(c)])
          ELSE /\ rcv' = [rcv EXCEPT ![c] = IdleReceive(c)]
               /\ UNCHANGED <<items, claimed, claims, attempts, rev, deliveries, held,
                              unclaims>>
     /\ UNCHANGED <<dlq, leases, instance, sw, lost, enqueued, acks>>

\* 3'. The claim transaction's response is lost (a timeout, a leader change), so the
\*     commit may or may not have landed. claimHead() catches the exception, and since it
\*     isn't LeaseNotFound, reconciles (review issue 50).
ClaimLost(c) ==
  /\ rcv[c].pc = "claiming"
  /\ lost < MaxLost
  /\ \/ /\ CanClaim(c)                                               \* it committed
        /\ Grant(c)
        /\ rcv' = [rcv EXCEPT ![c].pc = "reconciling", ![c].nonce = NewNonce(c)]
     \/ /\ UNCHANGED <<items, claimed, claims, attempts, rev, deliveries>> \* it didn't
        /\ rcv' = [rcv EXCEPT ![c].pc = "reconciling"]
  /\ lost' = lost + 1
  /\ UNCHANGED <<dlq, leases, instance, held, sw, unclaims, enqueued, acks>>

\* 4. reconcileClaim(): getResponse(claims/<id>). The claim is its own if the marker holds
\*    this attempt's value, <clientId>:<nonce>, on the lease it claimed with. Threads
\*    sharing the instance share its clientId and lease but not the nonce. The WorkItem
\*    then gets the marker's create revision as its token. Otherwise receive() rethrows,
\*    and the caller receives again.
Reconcile(c) ==
  LET r == rcv[c]
      m == claims[r.item]
      ours == HoldsValue(m, Inst(c), r.nonce) /\ m.lease = r.lease
  IN /\ r.pc = "reconciling"
     /\ IF ours
          THEN Handover(c, [item |-> r.item, token |-> m.token,
                            attempt |-> r.delivered + 1, nonce |-> r.nonce])
          ELSE /\ rcv' = [rcv EXCEPT ![c] = IdleReceive(c)]
               /\ UNCHANGED <<held, unclaims>>
     /\ UNCHANGED <<etcd, instance, sw, lost, enqueued, acks, deliveries>>

\* 4'. The re-read fails too. The claim may have committed and would then hold its item
\*     on a healthy lease, so reconcileClaim() records it in unresolvedClaims, keyed by
\*     this attempt's marker, for the sweeper to release, and returns null. receive()
\*     rethrows. Entries for one item don't replace each other: when two threads of one
\*     instance both lose a claim and its re-read, the one that committed stays recorded.
ReconcileLost(c) ==
  LET r == rcv[c]
  IN /\ r.pc = "reconciling"
     /\ lost < MaxLost
     /\ lost' = lost + 1
     /\ unresolved' = [unresolved EXCEPT ![Inst(c)] = @ \cup
                         {[item |-> r.item, attempt |-> r.delivered + 1,
                           nonce |-> r.nonce]}]
     /\ rcv' = [rcv EXCEPT ![c] = IdleReceive(c)]
     /\ UNCHANGED <<etcd, keepAlive, held, sw, unclaims, enqueued, acks, deliveries>>

---------------------------------------------------------------------------
(* A consumer's WorkItems *)

\* isStillClaimed(): claims/<id> holds the WorkItem's claimMarker (<clientId>:<nonce>)
\* and has its create revision
StillClaimed(c, w) ==
  LET m == claims[w.item]
  IN HoldsValue(m, Inst(c), w.nonce) /\ m.token = w.token

\* History: the live marker on w's item is the claim that c's receive was handed as w
OwnLiveClaim(c, w) ==
  LET m == claims[w.item]
  IN m # None /\ m.holder = c /\ m.token = w.token

\* WorkItem.ack(): If(isStillClaimed) Then(delete claims/<id>, claimed/<id>, and
\* attempts/<id>). True means done. False means the claim was lost, so the item may be
\* redone elsewhere.
Ack(c, w) ==
  LET i == w.item
  IN /\ w \in held[c]
     /\ held' = [held EXCEPT ![c] = @ \ {w}]
     /\ IF StillClaimed(c, w)
          THEN /\ claims' = [claims EXCEPT ![i] = None]
               /\ claimed' = [claimed EXCEPT ![i] = 0]
               /\ attempts' = [attempts EXCEPT ![i] = 0]
               /\ Bump(i)
               /\ acks' = acks \cup {<<i, OwnLiveClaim(c, w)>>}
          ELSE UNCHANGED <<claims, claimed, attempts, rev, acks>>
     /\ UNCHANGED <<items, dlq, leases, instance, rcv, sw, faults, enqueued, deliveries>>

\* WorkItem.requeue(): If(isStillClaimed) Then(put items/<id>, delete claims/<id> and
\* claimed/<id>). attempts/<id> stays, so the next claimHead() dead-letters an exhausted
\* item (review issue 16).
Requeue(c, w) ==
  LET i == w.item
  IN /\ w \in held[c]
     /\ held' = [held EXCEPT ![c] = @ \ {w}]
     /\ IF StillClaimed(c, w)
          THEN /\ items' = [items EXCEPT ![i] = NewRev(i)]
               /\ claims' = [claims EXCEPT ![i] = None]
               /\ claimed' = [claimed EXCEPT ![i] = 0]
               /\ Bump(i)
          ELSE UNCHANGED <<items, claims, claimed, rev>>
     /\ UNCHANGED <<attempts, dlq, leases, instance, rcv, sw, faults, enqueued, acks,
                    deliveries>>

\* givenBack(): put items/<id>, delete claims/<id> and claimed/<id>, and set attempts/<id>
\* to attempt - 1. The delivery never reached anyone, so it isn't counted.
GivenBack(i, attempt) ==
  /\ items' = [items EXCEPT ![i] = NewRev(i)]
  /\ claims' = [claims EXCEPT ![i] = None]
  /\ claimed' = [claimed EXCEPT ![i] = 0]
  /\ attempts' = [attempts EXCEPT ![i] = attempt - 1]
  /\ Bump(i)
  /\ deliveries' = [deliveries EXCEPT ![i] = @ - 1]

\* WorkItem.unclaim(), from claimUnclaimingOnCancel(): If(isStillClaimed) Then(givenBack)
Unclaim(c) ==
  LET r == rcv[c]
      i == r.item
      w == [item |-> i, token |-> r.rev, attempt |-> r.delivered + 1, nonce |-> r.nonce]
  IN /\ r.pc = "unclaiming"
     /\ IF StillClaimed(c, w)
          THEN GivenBack(i, w.attempt)
          ELSE UNCHANGED <<items, claims, claimed, attempts, rev, deliveries>>
     /\ rcv' = [rcv EXCEPT ![c] = IdleReceive(c)]
     /\ UNCHANGED <<dlq, leases, instance, held, sw, faults, enqueued, acks>>

---------------------------------------------------------------------------
(* The consumer lease: the visibility timeout *)

\* Lease <<x, g>> lapses in etcd, either because its consumer went quiet (a partition, a
\* GC pause) past the visibility timeout, or because it is an old lease the healer
\* already replaced. etcd deletes every claim marker on it, so those items become orphans
\* for the sweepers. The consumer keeps its WorkItems. It may still process them while
\* another consumer gets them anew, and then its ack() returns false. That duplicate
\* processing is the documented at-least-once contract, not a violation. So no invariant
\* forbids a held WorkItem whose claim is gone; they require only that a successful ack
\* be made under the live claim.
Lapse(x, g) ==
  LET l == <<x, g>>
      gone == {i \in Items : claims[i] # None /\ claims[i].lease = l}
  IN /\ l \in leases
     /\ g = leaseGen[x] => expiries < MaxExpiries
     /\ leases' = leases \ {l}
     /\ expiries' = IF g = leaseGen[x] THEN expiries + 1 ELSE expiries
     /\ claims' = [i \in Items |-> IF i \in gone THEN None ELSE claims[i]]
     /\ UNCHANGED <<rev, items, claimed, attempts, dlq, leaseGen, unresolved, rcv, held,
                    sw, faults, enqueued, acks, deliveries>>

\* SelfHealingKeepAlive heals a lapsed lease by granting a new one, which later claims
\* use. The establish hook is { true }: lapsed claims stay lost.
Heal(x) ==
  /\ <<x, leaseGen[x]>> \notin leases
  /\ leaseGen' = [leaseGen EXCEPT ![x] = @ + 1]
  /\ leases' = leases \cup {<<x, leaseGen[x] + 1>>}
  /\ UNCHANGED <<rev, items, claimed, claims, attempts, dlq, expiries, unresolved, rcv,
                 held, sw, faults, enqueued, acks, deliveries>>

\* jetcd's client-side deadline fires, and timeToLive can't confirm the lease, so the
\* healer grants a new lease while etcd still holds the old one (review issue 9). The old
\* lease lapses later, unrenewed.
Regrant(x) ==
  /\ <<x, leaseGen[x]>> \in leases
  /\ expiries < MaxExpiries
  /\ expiries' = expiries + 1
  /\ leaseGen' = [leaseGen EXCEPT ![x] = @ + 1]
  /\ leases' = leases \cup {<<x, leaseGen[x] + 1>>}
  /\ UNCHANGED <<rev, items, claimed, claims, attempts, dlq, unresolved, rcv, held, sw,
                 faults, enqueued, acks, deliveries>>

---------------------------------------------------------------------------
(* A consumer's sweeper: reclaimOrphans() and releaseUnresolvedClaims() *)

\* The id the loop is on: claimedIds come back in key order
Cur(s) == Min(sw[s].todo)

\* Done with the current id: on to the next, or the sweep ends
NextOrphan(s) ==
  LET rest == sw[s].todo \ {Cur(s)}
  IN IF rest = {} THEN IdleSweep
     ELSE [pc |-> "scanning", todo |-> rest, rev |-> 0, att |-> 0, nonce |-> None]

\* 1. getChildrenKeys(claimed/), then getChildrenKeys(claims/): the orphans are the
\*    claimed ids with no marker (review issue 54). The two listings are one action
\*    here. The move's guard, not the listing, keeps a sweep off a live claim. An id
\*    that only the two-read order would list (it left claimed/ between the reads) is
\*    skipped at step 2. It can also be moved at step 4, but only if it is an orphan by
\*    then, which is a move a later sweep also makes.
SweepList(s) ==
  LET orphans == {i \in Items : IsClaimed(i) /\ claims[i] = None}
  IN /\ sw[s].pc = "idle"
     /\ orphans # {}
     /\ sw' = [sw EXCEPT ![s] = [@ EXCEPT !.pc = "scanning", !.todo = orphans]]
     /\ UNCHANGED <<etcd, instance, rcv, held, faults, enqueued, acks, deliveries>>

\* 2. getResponse(claimed/<id>) reads its mod revision. If the key is gone, skip it.
SweepGetClaimed(s) ==
  LET i == Cur(s)
  IN /\ sw[s].pc = "scanning"
     /\ sw' = [sw EXCEPT ![s] = IF claimed[i] = 0 THEN NextOrphan(s)
                                ELSE [@ EXCEPT !.pc = "got", !.rev = claimed[i]]]
     /\ UNCHANGED <<etcd, instance, rcv, held, faults, enqueued, acks, deliveries>>

\* 3. getValue(attempts/<id>) picks where the orphan goes
SweepGetAttempts(s) ==
  /\ sw[s].pc = "got"
  /\ sw' = [sw EXCEPT ![s] = [@ EXCEPT !.pc = "counted", !.att = attempts[Cur(s)]]]
  /\ UNCHANGED <<etcd, instance, rcv, held, faults, enqueued, acks, deliveries>>

\* 4. If(claims/<id> absent, claimed/<id> mod = what step 2 read) Then(delete claimed/<id>,
\*    put items/<id>, or put dlq/<id> once attempts reach maxDeliveries). A sweeper that
\*    loses the race does nothing.
SweepMove(s) ==
  LET i == Cur(s)
  IN /\ sw[s].pc = "counted"
     /\ IF claims[i] = None /\ claimed[i] = sw[s].rev
          THEN /\ claimed' = [claimed EXCEPT ![i] = 0]
               /\ Bump(i)
               /\ IF sw[s].att >= MaxAttempts
                    THEN /\ dlq' = dlq \cup {i}
                         /\ UNCHANGED items
                    ELSE /\ items' = [items EXCEPT ![i] = NewRev(i)]
                         /\ UNCHANGED dlq
          ELSE UNCHANGED <<claimed, items, dlq, rev>>
     /\ sw' = [sw EXCEPT ![s] = NextOrphan(s)]
     /\ UNCHANGED <<claims, attempts, leases, instance, rcv, held, faults, enqueued, acks,
                    deliveries>>

\* releaseUnresolvedClaims(), run by each instance's periodic sweep (sweepSafely) after
\* reclaimOrphans(). The model doesn't order the two: an idle sweeper may start either, so
\* it covers that order too. Only the thread that runs sweepSafely releases: the sweeper of
\* each consumer that is its own instance. A failed read or transaction leaves the entry
\* for the next sweep. A transaction that committed but got no answer only delays the
\* entry's removal: the next read finds another nonce, or no marker.

\* 1. getResponse(claims/<id>) for an entry. If the marker holds that attempt's value, the
\*    claim committed and is released next. Otherwise it never committed, or it was
\*    reclaimed, and the entry is dropped (remove(claimMarker, claim)).
ReleaseRead(s) ==
  /\ sw[s].pc = "idle"
  /\ Inst(s) = s
  /\ \E e \in unresolved[s] :
       LET i == e.item
           m == claims[i]
       IN IF HoldsValue(m, s, e.nonce)
            THEN /\ sw' = [sw EXCEPT ![s] = [pc |-> "releasing", todo |-> {i},
                                              rev |-> m.token, att |-> e.attempt,
                                              nonce |-> e.nonce]]
                 /\ UNCHANGED unresolved
            ELSE /\ unresolved' = [unresolved EXCEPT ![s] = @ \ {e}]
                 /\ UNCHANGED sw
  /\ UNCHANGED <<etcd, keepAlive, rcv, held, faults, enqueued, acks, deliveries>>

\* 2. If(claims/<id> value = the entry's marker, create revision = what step 1 read)
\*    Then(givenBack), as unclaim() does. Either way the entry is dropped
\*    (remove(claimMarker, claim)).
ReleaseTxn(s) ==
  LET i == Cur(s)
      e == [item |-> i, attempt |-> sw[s].att, nonce |-> sw[s].nonce]
      m == claims[i]
  IN /\ sw[s].pc = "releasing"
     /\ IF HoldsValue(m, s, e.nonce) /\ m.token = sw[s].rev
          THEN GivenBack(i, e.attempt)
          ELSE UNCHANGED <<items, claims, claimed, attempts, rev, deliveries>>
     /\ unresolved' = [unresolved EXCEPT ![s] = @ \ {e}]
     /\ sw' = [sw EXCEPT ![s] = IdleSweep]
     /\ UNCHANGED <<dlq, leases, keepAlive, rcv, held, faults, enqueued, acks>>

---------------------------------------------------------------------------

Receive(c) ==
  \/ ReadHead(c) \/ ReadAttempts(c) \/ DeadLetter(c)
  \/ ClaimTxn(c) \/ ClaimLost(c) \/ Reconcile(c) \/ ReconcileLost(c) \/ Unclaim(c)

Finish(c) == \E w \in held[c] : Ack(c, w) \/ Requeue(c, w)

Release(s) == ReleaseRead(s) \/ ReleaseTxn(s)

Sweep(s) ==
  \/ SweepList(s) \/ SweepGetClaimed(s) \/ SweepGetAttempts(s) \/ SweepMove(s)
  \/ Release(s)

LeaseStep(x) == Heal(x) \/ Regrant(x) \/ \E g \in 0 .. MaxExpiries : Lapse(x, g)

\* Once every item is acked or dead-lettered, nothing is left to do, so the models don't
\* check for deadlock.
Next ==
  \/ Enqueue
  \/ \E c \in Consumers : Receive(c) \/ Finish(c) \/ Sweep(c)
  \/ \E x \in Instances : LeaseStep(x)

\* Every step of the code is fair. The faults (lapses, early re-grants, lost responses,
\* cancelled receives) are not.
Fairness ==
  /\ \A c \in Consumers :
       /\ WF_vars(ReadHead(c))
       /\ WF_vars(ReadAttempts(c))
       /\ WF_vars(DeadLetter(c))
       /\ WF_vars(ClaimTxn(c))
       /\ WF_vars(Reconcile(c))
       /\ WF_vars(Unclaim(c))
       /\ WF_vars(Finish(c))       \* a consumer eventually acks or requeues what it holds
       /\ WF_vars(Sweep(c))
       /\ WF_vars(Release(c))      \* every periodic sweep releases first
  /\ \A x \in Instances : WF_vars(Heal(x))

Spec == Init /\ [][Next]_vars /\ Fairness

---------------------------------------------------------------------------
(* Safety *)

\* Where an item is: queued, claimed (in flight or orphaned), acked, or dead-lettered
Places(i) ==
  (IF Queued(i) THEN {"queued"} ELSE {}) \cup (IF IsClaimed(i) THEN {"claimed"} ELSE {})
    \cup (IF Acked(i) THEN {"acked"} ELSE {}) \cup (IF Dead(i) THEN {"dead"} ELSE {})

\* No item is ever lost or duplicated. Every enqueued item is in exactly one place:
\* queued, claimed, acked, or dead-lettered. An ambiguous claim commit leaves the item
\* claimed, never gone (review issue 50).
NoItemLost == \A i \in Items : Cardinality(Places(i)) = IF Enqueued(i) THEN 1 ELSE 0

\* A live claim marker always sits beside its payload under claimed/. A sweep never moves
\* an item out from under a live claim (review issue 54).
ClaimMarkerHasPayload == \A i \in Items : claims[i] # None => IsClaimed(i)

\* At the etcd level, no item has two live claims at once. Ack, requeue, unclaim, and a
\* lease lapse delete a live marker. No claim transaction ever overwrites one with
\* another. reconcileClaim() (review issue 50) and the claim token (review issue 90)
\* both rely on this.
NoTwoLiveClaims ==
  [][\A i \in Items : claims[i] # None /\ claims'[i] # None => claims'[i] = claims[i]]_vars

\* An item is acked only by the consumer whose claim was live at the ack, and only with
\* that claim's WorkItem. A stale WorkItem, from a claim that lapsed before the same
\* instance claimed the item again, can't ack the new claim (review issue 90). acks
\* records this for each ack as it commits.
AckedOnlyUnderOwnLiveClaim == \A a \in acks : a[2]

\* c has the WorkItem for claim t on item i: it holds it, or is unclaiming it
Has(c, i, t) ==
  \/ \E w \in held[c] : w.item = i /\ w.token = t
  \/ rcv[c].pc = "unclaiming" /\ rcv[c].item = i /\ rcv[c].rev = t

\* The same, in the next state
HasNext(c, i, t) ==
  \/ \E w \in held'[c] : w.item = i /\ w.token = t
  \/ rcv'[c].pc = "unclaiming" /\ rcv'[c].item = i /\ rcv'[c].rev = t

\* No one takes a live claim from the consumer it was granted to. A claim ends only when
\* its lease lapses, when that consumer's own WorkItem acks, requeues, or unclaims it, or
\* when the sweeper releases that same unresolved attempt. Only a lapse leaves the holder
\* working on an item that is delivered again, the at-least-once case (review issues 90
\* and 50).
ClaimEndsOnlyByOwnerOrLapse ==
  [][\A i \in Items :
       (claims[i] # None /\ claims'[i] # claims[i]) =>
         LET m == claims[i]
         IN \/ m.lease \notin leases'
            \/ Has(m.holder, i, m.token) /\ ~HasNext(m.holder, i, m.token)
            \/ \E e \in unresolved[m.client] :
                 e.item = i /\ e.nonce = m.nonce /\ e \notin unresolved'[m.client]]_vars

\* A WorkItem naming an item's live claim belongs only to the consumer that claim was
\* granted to: no two receives are handed the same claim, not even two threads of one
\* instance, which share its clientId and lease (review issues 50 and 90)
LiveClaimHasOneHolder ==
  \A c \in Consumers : \A i \in Items :
    (claims[i] # None /\ Has(c, i, claims[i].token)) => claims[i].holder = c

\* Every live claim is known to the consumer it was granted to. That consumer has its
\* WorkItem, or is still reconciling the claim whose response was lost, or its instance
\* recorded the claim in unresolvedClaims after the re-read failed too. Such a claim waits
\* for the sweeper, which releases it (EveryItemFinishes and the mutants check that it
\* does). A claim nobody knows about sits on a healthy lease, and its item stays invisible
\* until the instance closes (review issues 50 and 22).
NoStrandedClaim ==
  \A i \in Items : claims[i] # None =>
    LET h == claims[i].holder
    IN \/ Has(h, i, claims[i].token)
       \/ rcv[h].pc = "reconciling" /\ rcv[h].item = i
       \/ \E e \in unresolved[Inst(h)] : e.item = i /\ e.nonce = claims[i].nonce

\* attempts/<id> counts the item's deliveries exactly (an unclaim or a release gives one
\* back) until it is acked, so reading it outside the claim transaction is safe (review
\* issue 16)
AttemptsCountDeliveries == \A i \in Items : ~Acked(i) => attempts[i] = deliveries[i]

\* No item is delivered more than maxDeliveries times. A poison item that is requeued
\* again and again ends in the dead-letter space (review issue 16).
NoDeliveryPastMaxAttempts == \A i \in Items : deliveries[i] <= MaxAttempts

\* An item is dead-lettered only after maxDeliveries deliveries (review issue 16)
DeadLetteredOnlyAfterMaxAttempts == \A i \in dlq : deliveries[i] >= MaxAttempts

(* Liveness *)

\* Every enqueued item is eventually acked or dead-lettered
EveryItemFinishes == \A i \in Items : Enqueued(i) ~> (Acked(i) \/ Dead(i))
=============================================================================
