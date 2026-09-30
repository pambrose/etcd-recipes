# TLA+ specifications

The recipes are distributed protocols: several processes coordinate through etcd, with
leases that expire and watches that reconnect. Unit and integration tests exercise the
interleavings a test run happens to hit. These specs let [TLC](https://github.com/tlaplus/tlaplus)
check every interleaving of a small model: each process step that makes its own etcd RPC is
a separate action, and leases can expire between any two of them.

Each `<Module>.tla` models one recipe's protocol as the Kotlin code implements it (the
header comment maps it to the code); each `<Module>[<Variant>].cfg` is a model of it:
constants, invariants, and properties.

## Running

```bash
make tla                                  # every model
./specs/tlc.sh CountBarrier.cfg           # one model
```

`tlc.sh` downloads TLC once into `specs/.tools/` (gitignored), pinned by version and
SHA-256, and needs only Java. CI runs every model on every pull request and push.

## The specs

### `CountBarrier.tla` — `DistributedBarrierWithCount`

Parties join the round in progress (a round is `/ready`'s create revision), register a
lease-bound waiting key under `waiting/<round>/`, and leave once `/ready` no longer names
their round. The party that sees the round full deletes `/ready` guarded on the round,
retried, and leaves only after. Parties loop, time out, and have their waiting keys' leases
expire and heal.

| Property | Kind | Guarantees |
| --- | --- | --- |
| `NoEarlyTrip` | invariant | A round trips only after `N` parties were waiting in it at once (review issue 41) |
| `ReleasedAfterTrip` | invariant | A party leaves released only after its round's `/ready` was deleted (review issue 40) |
| `FullRoundsTrip` | liveness | Without timeouts, every full round trips, even when releases fail transiently |

Models: `CountBarrier.cfg` (3 parties, `N` = 2, 2 waits each, timeouts, one lease expiry each:
8.5M states, about 15 s) and `CountBarrierLiveness.cfg` (2 parties, no timeouts).

### `ReadWriteLock.tla` — `DistributedReadWriteLock`

Each acquisition creates a lease-bound entry whose create revision is its place in line; a
writer is admitted when nothing ranks before it, a reader when no writer does. A write→read
downgrade's read entry inherits the write entry's rank, but only while that write entry
still exists (checked after the read entry is created). Clients follow scripted
lock/unlock programs, including a downgrade; entries' leases expire (the client hears
later), a parked `tryLock` gives up, and a revoke can fail, orphaning an entry until its
TTL.

| Property | Kind | Guarantees |
| --- | --- | --- |
| `MutualExclusion` | invariant | A writer excludes everyone else (review issues 7 and 1) |
| `FifoOrder` | invariant | No holder has another client's conflicting entry ranked ahead of it |
| `InheritedRankIsBacked` | invariant | A downgrade holds on an inherited rank only if the write entry still existed when the read entry was written (review issue 1) |
| `AdmitsOnlyInLine` | action property | A scan admits a client only if its own entry is still in the snapshot |
| `WaitersAdmitted` | liveness | Every parked waiter is admitted once the entries ahead of it go (review issues 1 and 6) |
| `AllFinish` | liveness | Every client gets through its calls |

The safety properties range over holders whose entries still exist in etcd: a client whose
lease expired can go on believing it holds until it notices, which is what fencing tokens
are for.

This spec found two bugs, both fixed:

- **A downgrade's fencing token fenced out the writer queued behind it.** A downgraded read
  took its entry's own, newer revision as its token, so the writer admitted after it got a
  smaller token than one already issued. The token is now the hold's rank.
- **An acquisition whose entry had just expired could be admitted.** The scan never checked
  for its own entry, so a client whose lease expired unnoticed was admitted with no place in
  line, possibly alongside a later writer. It now restarts at the tail, as the semaphore
  already did.

Models: `ReadWriteLock.cfg` (3 clients with a downgrade, one expiry, timeout, and failed
revoke: 5.6M states, about 40 s) and `ReadWriteLockLiveness.cfg` (no timeouts).

### `WorkQueue.tla` — `DistributedWorkQueue`

At-least-once delivery. A consumer reads the head, then claims it in one transaction:
the item moves to `claimed/`, a claim marker bound to the consumer's lease goes into
`claims/`, and the attempt count goes up. `ack`, `requeue`, and `unclaim` are guarded on the
claim still being theirs. A lapsed lease deletes its markers, so the sweepers (two, running
concurrently) return those items to the queue, or dead-letter them past `maxDeliveries`. A
claim whose response is lost is reconciled by re-reading its marker. The model covers two
consumers (or two threads sharing one instance), lease lapses and early re-grants, lost
claim responses and failed re-reads, and cancelled receives.

| Property | Kind | Guarantees |
| --- | --- | --- |
| `NoItemLost` | invariant | Every enqueued item is in exactly one place: queued, claimed, acked, or dead-lettered |
| `ClaimMarkerHasPayload` | invariant | A live claim marker always has its `claimed/` payload |
| `AckedOnlyUnderOwnLiveClaim` | invariant | An ack commits only under the acking consumer's own live claim (review issue 90) |
| `LiveClaimHasOneHolder` | invariant | Only one receive is ever handed a given claim |
| `NoStrandedClaim` | invariant | Every live claim is known to the consumer it was granted to, or awaits its sweeper's release (review issue 50) |
| `AttemptsCountDeliveries` | invariant | The attempt count equals the real delivery count |
| `NoDeliveryPastMaxAttempts` | invariant | No item is delivered more than `maxDeliveries` times (review issue 16) |
| `DeadLetteredOnlyAfterMaxAttempts` | invariant | An item is dead-lettered only after `maxDeliveries` deliveries |
| `NoTwoLiveClaims` | action property | A claim never overwrites a live marker |
| `ClaimEndsOnlyByOwnerOrLapse` | action property | A live claim ends only by its lease lapsing, its own holder, or the release of that same unresolved attempt |
| `EveryItemFinishes` | liveness | Every enqueued item is eventually acked or dead-lettered |

A consumer that keeps working after its lease lapsed is the at-least-once contract
(duplicate processing), not a violation.

This spec found three bugs, all fixed:

- **A lost claim response could be reconciled into another thread's claim.** Threads
  sharing one instance share its clientId and lease, which is all the reconciliation
  checked, so two threads could process one item under one claim. Each claim marker now
  carries a nonce unique to its attempt.
- **A lost claim response followed by a failed re-read stranded the claim** under the
  consumer's healthy lease until it restarted. The consumer now remembers such claims, and
  its sweeper gives back any that committed.
- **One such record could overwrite another** for the same item (a shared instance, one
  outage losing four responses), stranding the committed one. The records are keyed by
  claim marker, not item id.

Models: `WorkQueue.cfg` (2 consumers, 2 items, `maxDeliveries` = 2, a lapse, a lost response,
a cancelled receive: 7.4M states, about 25 s), `WorkQueueLiveness.cfg` (1 item, two lost
responses), and `WorkQueueShared.cfg` (two threads sharing one instance). Delayed items,
`enqueueAll`, and dead-letter requeue/purge aren't modeled; revisions are counted per item,
which is exact for these compares, which each check one item's keys.

## Writing a spec

- Model what the code does, not an idealized protocol. Split a process's work into
  separate actions wherever the code makes separate RPCs, and let leases expire and
  responses get lost between them.
- Keep every model finite through its constants (no state constraints, which make liveness
  checking unsound), and small enough that `make tla` stays fast.
- Name each invariant for what it guarantees, and cite the review issue or bug it covers.
- **Check that the spec can fail.** Reintroduce each bug it's meant to catch in a scratch
  copy, and confirm that TLC reports the invariant you expect. A spec that never fails
  proves nothing.
