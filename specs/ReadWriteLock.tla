---------------------------- MODULE ReadWriteLock ----------------------------
(***************************************************************************)
(* The queueing protocol of DistributedReadWriteLock                       *)
(* (etcd-recipes-core/.../lock/DistributedReadWriteLock.kt, with           *)
(* AcquisitionLease.kt and WaiterSupport.kt).                              *)
(*                                                                         *)
(* A thread calling lock() on the read or write side (acquire()):          *)
(*   1. creates a lease-bound entry under <lockPath>/ in a transaction.    *)
(*      Its create revision is its rank, its place in line. A read taken   *)
(*      while the thread holds the write lock (a write->read downgrade)    *)
(*      writes "rank:<the write hold's rank>" as the entry's value, so it  *)
(*      inherits the write entry's place (effectiveRank());                *)
(*   2. GETs its entry to learn its create revision; if the entry is       *)
(*      already gone, its lease died, and it starts over with a new one;   *)
(*   3. evaluates admission on one ranged read (nearestConflict()): an     *)
(*      entry ranked before it conflicts when either side is a writer. Its *)
(*      own entry and its own write hold's entry never count;              *)
(*   4. with a conflict, parks on the DELETE of the nearest one            *)
(*      (awaitKeyDeletion(), a revision-anchored watch), then goes back    *)
(*      to 3;                                                              *)
(*   5. with none, a downgrade first checks that the write entry it took   *)
(*      its rank from still exists (isKeyPresent()) and, if not, starts    *)
(*      over as an ordinary read at the tail; then it publishes the hold   *)
(*      and claims the attempt's phase, WAITING -> HOLDING;                *)
(*   6. unlock() drops the hold and revokes the lease, deleting the entry. *)
(* Each restart runs acquire()'s finally block first, which revokes the    *)
(* abandoned attempt's lease. A tryLock() may give up while parked; its    *)
(* revoke gets a single attempt (closePromptly()). Any revoke can fail     *)
(* (review issue 81), leaving the entry until its TTL runs out.            *)
(*                                                                         *)
(* etcd is a revision counter plus the set of live entries, each with its  *)
(* create revision (which is also its key), side, owner, and value (0 for  *)
(* the clientId, else a carried rank). A lease can expire at any moment:   *)
(* etcd deletes the entry at once, and the owner hears about it later      *)
(* (AcquisitionLease's onFatal). A waiting attempt then goes DEAD and      *)
(* starts over; a holder runs lockLost() and drops its hold.               *)
(*                                                                         *)
(* Each client is one thread running the lock calls in Prog[c]: "LW"/"LR"  *)
(* lock the write/read side, "UW"/"UR" unlock it. An unlock whose hold was *)
(* lost, or whose lock timed out, does nothing (it returns false). The     *)
(* programs avoid reentrant locks (a local hold count, with no etcd step)  *)
(* and read->write upgrades (they throw).                                  *)
(*                                                                         *)
(* Abstracted: the lease grant RPC (a lease that dies before the entry is  *)
(* written makes the put fail and acquire() throw, which touches no        *)
(* entry); the hold count, fencing tokens, and lock-lost listeners (all    *)
(* local bookkeeping); close() (review issue 44, a race between a          *)
(* closer and an acquirer inside one instance, with no etcd step of its    *)
(* own); deadline checks other than while parked (giving up anywhere else  *)
(* deletes or orphans a not-yet-admitted entry the same way); and the      *)
(* watch machinery, whose anchoring at the snapshot's revision makes the   *)
(* DELETE of the watched key a reliable wakeup, so a parked waiter wakes   *)
(* once that key is gone. A scan followed by a local win (publish the      *)
(* hold, CAS the phase) is one step: the win reads no etcd state, and      *)
(* making the holder a holder earlier only adds states to check. Keys are  *)
(* create revisions, so the sibling-path and '/'-in-clientId key-string    *)
(* bugs (review issues 6 and 7) appear here only as their effect: an entry *)
(* of the wrong lock, or of the wrong side. Faults (expiries, timeouts,    *)
(* failed revokes) are budgeted across all clients, not per client.        *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS
  Prog,               \* Prog[c]: the lock calls client c makes, in order
  MaxExpiries,        \* how many kept-alive leases may expire, in all
  MaxTimeouts,        \* how many parked tryLock() calls may give up, in all
  MaxRevokeFailures   \* how many revokes may fail, in all, leaving entries to their TTLs

Clients == DOMAIN Prog
Sides == {"read", "write"}
Calls == {"LW", "LR", "UW", "UR"}
MaxLen == 4

ASSUME /\ \A c \in Clients : Prog[c] \in Seq(Calls) /\ Len(Prog[c]) <= MaxLen
       /\ MaxExpiries \in Nat /\ MaxTimeouts \in Nat /\ MaxRevokeFailures \in Nat

\* Every entry is created once and deleted once (2 revisions). A client makes at most
\* MaxLen acquisitions, each restarting once when a downgrade's write entry is gone, and
\* an attempt restarts once per lease expiry.
MaxRev == 2 * (Cardinality(Clients) * 2 * MaxLen + MaxExpiries)
Revs == 1 .. MaxRev

\* A local hold (EntryData): the entry's key and its rank. NoHold is a hold's absence.
Holds == [key : {0} \cup Revs, rank : {0} \cup Revs]
NoHold == [key |-> 0, rank |-> 0]

VARIABLES
  rev,       \* etcd's store revision
  entries,   \* the live entries: [key (create revision), side, owner, val, backed]
  pcnt,      \* each client's position in Prog
  pc,        \* each client's step within its current call
  key,       \* the current attempt's entry; 0 when there is none
  inh,       \* the write hold a downgrade attempt took its rank from (inheritedFrom)
  mayInh,    \* mayInheritWriteRank
  watch,     \* the entry a parked attempt waits on
  dead,      \* the current attempt's phase is DEAD (its lease's fatal was delivered)
  rHold,     \* readHolds[thread]
  wHold,     \* writeHolds[thread]
  lost,      \* each client's entries whose leases expired, not yet reported to it
  faults     \* the expiries, timeouts, and failed revokes so far

vars == <<rev, entries, pcnt, pc, key, inh, mayInh, watch, dead, rHold, wHold, lost, faults>>

\* The ghost field `backed` records whether a downgraded read entry was written while
\* the write entry it takes its rank from still existed (always TRUE for other entries).
\* Nothing in the protocol reads it.
Entries == [key : Revs, side : Sides, owner : Clients, val : {0} \cup Revs, backed : BOOLEAN]

LiveKeys == {e.key : e \in entries}
Entry(k) == CHOOSE e \in entries : e.key = k

\* effectiveRank(): the carried "rank:<n>", else the create revision
ERank(e) == IF e.val # 0 THEN e.val ELSE e.key

Conflict(s, t) == s = "write" \/ t = "write"

Call(c) == Prog[c][pcnt[c]]
Running(c) == pcnt[c] <= Len(Prog[c])
SideOf(call) == IF call \in {"LW", "UW"} THEN "write" ELSE "read"
Side(c) == SideOf(Call(c))
HoldOf(c, s) == IF s = "write" THEN wHold[c] ELSE rHold[c]

\* ownRank: the inherited rank, else the entry's own create revision
OwnRank(c) == IF inh[c] # NoHold THEN inh[c].rank ELSE key[c]

\* The leases a client keeps alive: its current attempt's and its holds'
Active(c) == {key[c], wHold[c].key, rHold[c].key} \ {0}

TypeOK ==
  /\ rev \in 0 .. MaxRev
  /\ entries \subseteq Entries
  /\ \A c \in Clients : pcnt[c] \in 1 .. Len(Prog[c]) + 1
  /\ pc \in [Clients -> {"idle", "get", "scan", "wait", "check", "retry", "create"}]
  /\ key \in [Clients -> {0} \cup Revs]
  /\ inh \in [Clients -> Holds]
  /\ mayInh \in [Clients -> BOOLEAN]
  /\ watch \in [Clients -> {0} \cup Revs]
  /\ dead \in [Clients -> BOOLEAN]
  /\ rHold \in [Clients -> Holds]
  /\ wHold \in [Clients -> Holds]
  /\ lost \in [Clients -> SUBSET Revs]
  /\ faults \in [expired : 0 .. MaxExpiries, timedOut : 0 .. MaxTimeouts,
                 revokeFailed : 0 .. MaxRevokeFailures]

Init ==
  /\ rev = 0
  /\ entries = {}
  /\ pcnt = [c \in Clients |-> 1]
  /\ pc = [c \in Clients |-> "idle"]
  /\ key = [c \in Clients |-> 0]
  /\ inh = [c \in Clients |-> NoHold]
  /\ mayInh = [c \in Clients |-> TRUE]
  /\ watch = [c \in Clients |-> 0]
  /\ dead = [c \in Clients |-> FALSE]
  /\ rHold = [c \in Clients |-> NoHold]
  /\ wHold = [c \in Clients |-> NoHold]
  /\ lost = [c \in Clients |-> {}]
  /\ faults = [expired |-> 0, timedOut |-> 0, revokeFailed |-> 0]

\* A revoke of k's lease deletes k, unless it fails (ok = FALSE) and k waits out its TTL.
\* Either way the closed lease reports nothing more. Callers count a failure in faults.
RevokeOk(k) == IF faults.revokeFailed < MaxRevokeFailures /\ k \in LiveKeys THEN BOOLEAN ELSE {TRUE}
Failed(ok) == IF ok THEN 0 ELSE 1

Revoke(c, k, ok) ==
  /\ IF ok /\ k \in LiveKeys
       THEN /\ entries' = {e \in entries : e.key # k}
            /\ rev' = rev + 1
       ELSE UNCHANGED <<entries, rev>>
  /\ lost' = [lost EXCEPT ![c] = @ \ {k}]

\* The current attempt ends (won, or given up): the call is over
EndAttempt(c) ==
  /\ pc' = [pc EXCEPT ![c] = "idle"]
  /\ pcnt' = [pcnt EXCEPT ![c] = @ + 1]
  /\ key' = [key EXCEPT ![c] = 0]
  /\ inh' = [inh EXCEPT ![c] = NoHold]
  /\ mayInh' = [mayInh EXCEPT ![c] = TRUE]
  /\ watch' = [watch EXCEPT ![c] = 0]
  /\ dead' = [dead EXCEPT ![c] = FALSE]

\* 5b. Admitted: holdsFor(side)[me] = EntryData(...), then the WAITING -> HOLDING CAS wins
Admit(c) ==
  LET h == [key |-> key[c], rank |-> OwnRank(c)]
  IN /\ IF Side(c) = "write"
          THEN /\ wHold' = [wHold EXCEPT ![c] = h]
               /\ UNCHANGED rHold
          ELSE /\ rHold' = [rHold EXCEPT ![c] = h]
               /\ UNCHANGED wHold
     /\ EndAttempt(c)
     /\ UNCHANGED <<rev, entries, lost, faults>>

\* 1. acquire()'s create transaction: If(entry absent) Then(put value, lease). A read taken
\*    under this thread's write hold carries that hold's rank as "rank:<n>".
Create(c) ==
  /\ Running(c)
  /\ Call(c) \in {"LW", "LR"}
  /\ \/ pc[c] = "idle"    \* a new lock() call
     \/ pc[c] = "create"  \* the next pass of acquire()'s outer loop
  /\ LET may == IF pc[c] = "idle" THEN TRUE ELSE mayInh[c]
         from == IF Side(c) = "read" /\ may THEN wHold[c] ELSE NoHold
         k == rev + 1
     IN /\ entries' = entries \cup
             {[key |-> k, side |-> Side(c), owner |-> c, val |-> from.rank,
               backed |-> from = NoHold \/ from.key \in LiveKeys]}
        /\ rev' = k
        /\ key' = [key EXCEPT ![c] = k]
        /\ inh' = [inh EXCEPT ![c] = from]
        /\ mayInh' = [mayInh EXCEPT ![c] = may]
  /\ pc' = [pc EXCEPT ![c] = "get"]
  /\ UNCHANGED <<pcnt, watch, dead, rHold, wHold, lost, faults>>

\* 2. getResponse(entryKey): its create revision, or (entry already gone) a fresh attempt
GetOwn(c) ==
  /\ pc[c] = "get"
  /\ pc' = [pc EXCEPT ![c] = IF key[c] \in LiveKeys THEN "scan" ELSE "retry"]
  /\ UNCHANGED <<rev, entries, pcnt, key, inh, mayInh, watch, dead, rHold, wHold, lost, faults>>

\* The conflict set of nearestConflict(): entries ranked before the attempt, less its own
\* entry and its own write hold's, that are writers or (for a writer) anything
Conflicts(c) ==
  {e \in entries : /\ ERank(e) < OwnRank(c)
                   /\ e.key # key[c]
                   /\ e.key # wHold[c].key
                   /\ Conflict(Side(c), e.side)}

\* 3. The inner loop's top: a DEAD phase restarts; else nearestConflict()'s one ranged read,
\*    which also restarts the attempt when its own entry is gone from the snapshot (a lease
\*    expiry not yet noticed): such an entry has no place in line
Scan(c) ==
  /\ pc[c] = "scan"
  /\ IF dead[c] \/ key[c] \notin LiveKeys
       THEN /\ pc' = [pc EXCEPT ![c] = "retry"]
            /\ UNCHANGED <<rev, entries, pcnt, key, inh, mayInh, watch, dead, rHold, wHold, lost, faults>>
       ELSE LET C == Conflicts(c)
            IN IF C # {}
                 \* 4. park on the nearest (latest-ranked) conflict
                 THEN \E e \in C :
                        /\ \A f \in C : ERank(f) <= ERank(e)
                        /\ watch' = [watch EXCEPT ![c] = e.key]
                        /\ pc' = [pc EXCEPT ![c] = "wait"]
                        /\ UNCHANGED <<rev, entries, pcnt, key, inh, mayInh, dead, rHold, wHold, lost, faults>>
                 ELSE IF inh[c] # NoHold
                   THEN /\ pc' = [pc EXCEPT ![c] = "check"]
                        /\ UNCHANGED <<rev, entries, pcnt, key, inh, mayInh, watch, dead, rHold, wHold, lost, faults>>
                   ELSE Admit(c)

\* 5a. A downgrade's isKeyPresent(inheritedFrom.entryKey): gone means mayInheritWriteRank =
\*     false and a fresh attempt at the tail; present means admitted, unless the phase CAS
\*     loses to a fatal (then the hold is rolled back and the attempt restarts)
Check(c) ==
  /\ pc[c] = "check"
  /\ IF inh[c].key \notin LiveKeys
       THEN /\ mayInh' = [mayInh EXCEPT ![c] = FALSE]
            /\ pc' = [pc EXCEPT ![c] = "retry"]
            /\ UNCHANGED <<rev, entries, pcnt, key, inh, watch, dead, rHold, wHold, lost, faults>>
       ELSE IF dead[c]
         THEN /\ pc' = [pc EXCEPT ![c] = "retry"]
              /\ UNCHANGED <<rev, entries, pcnt, key, inh, mayInh, watch, dead, rHold, wHold, lost, faults>>
         ELSE Admit(c)

\* 4b. awaitKeyDeletion() returns: the watched entry's DELETE arrived (the anchored watch,
\*     or the pre-live recheck saw it gone), or a fatal counted the latch down
Wake(c) ==
  /\ pc[c] = "wait"
  /\ watch[c] \notin LiveKeys \/ dead[c]
  /\ pc' = [pc EXCEPT ![c] = "scan"]
  /\ watch' = [watch EXCEPT ![c] = 0]
  /\ UNCHANGED <<rev, entries, pcnt, key, inh, mayInh, dead, rHold, wHold, lost, faults>>

\* 4c. A tryLock() deadline passes while parked: return false; finally closePromptly()
Timeout(c) ==
  /\ pc[c] = "wait"
  /\ faults.timedOut < MaxTimeouts
  /\ \E ok \in RevokeOk(key[c]) :
       /\ Revoke(c, key[c], ok)
       /\ faults' = [faults EXCEPT !.timedOut = @ + 1, !.revokeFailed = @ + Failed(ok)]
  /\ EndAttempt(c)
  /\ UNCHANGED <<rHold, wHold>>

\* acquire()'s finally before `continue@outer`: lease.close() revokes the attempt's entry
Retry(c) ==
  /\ pc[c] = "retry"
  /\ \E ok \in RevokeOk(key[c]) :
       /\ Revoke(c, key[c], ok)
       /\ faults' = [faults EXCEPT !.revokeFailed = @ + Failed(ok)]
  /\ pc' = [pc EXCEPT ![c] = "create"]
  /\ key' = [key EXCEPT ![c] = 0]
  /\ inh' = [inh EXCEPT ![c] = NoHold]
  /\ dead' = [dead EXCEPT ![c] = FALSE]
  /\ UNCHANGED <<pcnt, mayInh, watch, rHold, wHold>>

\* 6. release(): holds.remove(me), then lease.close() revokes the entry. With the hold
\*    already lost (lockLost() moved it to dispossessed), or never won, unlock() returns false.
Release(c) ==
  /\ pc[c] = "idle"
  /\ Running(c)
  /\ Call(c) \in {"UW", "UR"}
  /\ LET h == HoldOf(c, Side(c))
     IN IF h = NoHold
          THEN UNCHANGED <<rev, entries, rHold, wHold, lost, faults>>
          ELSE /\ \E ok \in RevokeOk(h.key) :
                    /\ Revoke(c, h.key, ok)
                    /\ faults' = [faults EXCEPT !.revokeFailed = @ + Failed(ok)]
               /\ IF Side(c) = "write"
                    THEN /\ wHold' = [wHold EXCEPT ![c] = NoHold]
                         /\ UNCHANGED rHold
                    ELSE /\ rHold' = [rHold EXCEPT ![c] = NoHold]
                         /\ UNCHANGED wHold
  /\ pcnt' = [pcnt EXCEPT ![c] = @ + 1]
  /\ UNCHANGED <<pc, key, inh, mayInh, watch, dead>>

\* A kept-alive lease expires (a partition, a long pause): etcd deletes its entry now, and
\* the owner's keep-alive stream will report it later
Expire(e) ==
  /\ e.key \in Active(e.owner)
  /\ faults.expired < MaxExpiries
  /\ entries' = entries \ {e}
  /\ rev' = rev + 1
  /\ faults' = [faults EXCEPT !.expired = @ + 1]
  /\ lost' = [lost EXCEPT ![e.owner] = @ \cup {e.key}]
  /\ UNCHANGED <<pcnt, pc, key, inh, mayInh, watch, dead, rHold, wHold>>

\* An entry whose revoke failed outlives its TTL: etcd deletes it, and nobody is told
ExpireOrphan(e) ==
  /\ e.key \notin Active(e.owner)
  /\ entries' = entries \ {e}
  /\ rev' = rev + 1
  /\ UNCHANGED <<pcnt, pc, key, inh, mayInh, watch, dead, rHold, wHold, lost, faults>>

\* AcquisitionLease's onFatal reaches onEntryFatal(): a waiting attempt goes DEAD (and its
\* latch is counted down); a holding one runs lockLost(), which drops the hold
Notice(c) ==
  \E k \in lost[c] :
    /\ lost' = [lost EXCEPT ![c] = @ \ {k}]
    /\ IF k = key[c]
         THEN /\ dead' = [dead EXCEPT ![c] = TRUE]
              /\ UNCHANGED <<rHold, wHold>>
         ELSE /\ wHold' = [wHold EXCEPT ![c] = IF @.key = k THEN NoHold ELSE @]
              /\ rHold' = [rHold EXCEPT ![c] = IF @.key = k THEN NoHold ELSE @]
              /\ UNCHANGED dead
    /\ UNCHANGED <<rev, entries, pcnt, pc, key, inh, mayInh, watch, faults>>

ClientStep(c) ==
  \/ Create(c) \/ GetOwn(c) \/ Scan(c) \/ Check(c) \/ Wake(c)
  \/ Timeout(c) \/ Retry(c) \/ Release(c) \/ Notice(c)

\* A waiter alone behind a holder that never releases (or after every client is done)
\* has nothing to do, so the models don't check for deadlock.
Next ==
  \/ \E c \in Clients : ClientStep(c)
  \/ \E e \in entries : Expire(e) \/ ExpireOrphan(e)

Fairness ==
  /\ \A c \in Clients :
       /\ WF_vars(Create(c))
       /\ WF_vars(GetOwn(c))
       /\ WF_vars(Scan(c))
       /\ WF_vars(Check(c))
       /\ WF_vars(Wake(c))
       /\ WF_vars(Retry(c))
       /\ WF_vars(Release(c))
       /\ WF_vars(Notice(c))
  /\ WF_vars(\E e \in entries : ExpireOrphan(e))  \* a TTL always runs out

Spec == Init /\ [][Next]_vars /\ Fairness

---------------------------------------------------------------------------
(* Safety                                                                  *)
(*                                                                         *)
(* A holder is a client with a local hold whose entry still exists in      *)
(* etcd. A client whose lease expired can go on believing it holds until   *)
(* its keep-alive stream reports the expiry (lockLost()): a partition or a *)
(* GC pause can make that last longer than the gap before etcd's next      *)
(* grant. That stale holder is the documented limitation that fencing      *)
(* tokens address (review issue 83, `EtcdLock.fencingToken`: the entry's   *)
(* create revision, later than every conflicting hold granted before it),  *)
(* not something the entries can prevent, so the invariants range over     *)
(* holders whose entries exist.                                            *)

LiveHold(c, s) == HoldOf(c, s).key \in LiveKeys
HeldEntry(c, s) == Entry(HoldOf(c, s).key)

\* A writer excludes everyone else: no reader and writer, or two writers, hold at once
\* (review issue 7, where a writer invisible to readers let both hold; and issue 1's
\* warning against admitting a downgrade at once, which lets a queued writer in).
MutualExclusion ==
  \A c, d \in Clients, s, t \in Sides :
    (c # d /\ Conflict(s, t) /\ LiveHold(c, s)) => ~LiveHold(d, t)

\* FIFO: no holder has another client's conflicting entry ranked ahead of it that holds a
\* real place in line, so a later arrival (a reader behind a queued writer, say) never
\* overtakes an earlier one, and no writer gets in ahead of a downgraded read's inherited
\* place (the class KDoc's fairness; review issue 1's rank). A thread's own entries don't
\* count: its write hold is excluded from its own scans, and the write entry of a
\* downgrade can outlive its release (a failed revoke). A downgrade entry written after its
\* write entry was gone carries a rank it was never entitled to: it is re-queued at the
\* tail before it can be admitted, so it is exempt here and covered below.
FifoOrder ==
  \A c \in Clients, s \in Sides :
    LiveHold(c, s) =>
      \A e \in entries :
        (e.owner # c /\ e.backed /\ Conflict(s, e.side))
          => ERank(e) >= ERank(HeldEntry(c, s))

\* A downgraded read holds on an inherited rank only if its entry was written while the
\* write entry it came from still existed, so it can't jump ahead of a writer admitted
\* after that write entry vanished (review issue 1's fix: the rank is inherited only while
\* the write entry exists, checked after the read entry is created).
InheritedRankIsBacked ==
  \A c \in Clients, s \in Sides : LiveHold(c, s) => HeldEntry(c, s).backed

(* Liveness, without timeouts *)

\* A parked waiter is eventually admitted once the entries ahead of it are released or
\* expire (review issue 1: a downgrade queued behind a writer that waits on the
\* downgrader's own write hold parks both forever; review issue 6: a scan that counts a
\* sibling lock's entries can wait on one its own thread holds).
WaitersAdmitted ==
  \A c \in Clients, i \in 1 .. MaxLen :
    (pc[c] = "wait" /\ pcnt[c] = i) ~> (pcnt[c] > i)

\* Every client gets through its calls
AllFinish == <>(\A c \in Clients : ~Running(c))

---------------------------------------------------------------------------
(* Models: each client takes one or two locks, mixing a write->read        *)
(* downgrade, plain writes, and plain reads.                               *)

\* Client 1 downgrades (write, then read under it, then drops the write first); client 2
\* writes then reads; client 3 reads then writes.
SafetyProg ==
  << <<"LW", "LR", "UW", "UR">>,
     <<"LW", "UW", "LR", "UR">>,
     <<"LR", "UR", "LW", "UW">> >>

\* The issue 1 shape: a downgrader, a writer that can queue behind its write hold, and a reader
LivenessProg ==
  << <<"LW", "LR", "UW", "UR">>,
     <<"LW", "UW">>,
     <<"LR", "UR">> >>
\* A scan admits a client only when the snapshot still holds its own entry. Otherwise a
\* client whose lease expired unnoticed is admitted with no place in line, alongside a
\* writer admitted after the expiry. (Found by this spec; the scan used to ignore it.)
AdmitsOnlyInLine ==
  [][\A c \in Clients :
       LET admitted == \/ (wHold'[c] # wHold[c] /\ wHold'[c].key = key[c])
                       \/ (rHold'[c] # rHold[c] /\ rHold'[c].key = key[c])
       IN (pc[c] = "scan" /\ admitted) => key[c] \in LiveKeys]_vars

=============================================================================
