# Distributed Lock Suite — Design (product idea #1)

Approved 2026-07-11. Three sequential PRs, each merged before the next:
DistributedMutex → DistributedReadWriteLock → DistributedSemaphore. All in a new
`io.etcd.recipes.lock` package.

## Decisions (user-approved)

- **Cooperative lock-lost**: mark not-held + `LockLostListener` + `ConnectionState.LOST`;
  thread interrupt is opt-in (`interruptOnLockLoss`, default false) — critical sections
  are inline user code, unlike LeaderSelector's framework-owned callback.
- **Semaphore is single-permit** (`acquire()/tryAcquire(timeout)/release()`).
- **Thread-per-acquisition** (Curator parity): each non-reentrant acquisition grants a
  fresh lease and queues in etcd; a second thread on one instance queues like a second
  process. Reentrancy is per-thread hold counts. A thread that dies holding never
  unlocks until `close()` (documented, same as Curator).

## Cross-cutting mechanics

- `AcquisitionLease` (internal): lease grant + immediate keep-alive with a
  discriminating observer (transient → Suspended/exceptions; fatal = onCompleted or
  lease-not-found → the attempt's fatal hook). **Never self-healed** — an expired lock
  lease means etcd already promoted the next waiter. `close()` revokes; revoke is the
  sole authoritative abort (jetcd's lock RPC internally retries on safe-redo failures
  and `future.cancel` is best-effort).
- `EtcdLock` interface (ships in PR 1): `lock()`, `tryLock(timeout)`, `unlock(): Boolean`
  (owner → true; dispossessed → false; non-owner → IllegalMonitorStateException),
  `isHeldByCurrentThread`, `isLocked` (advisory), `holdCount`, lock-lost listeners,
  `withLock { }`. Deliberately not `java.util.concurrent.locks.Lock`.
- Wait strategies: mutex — none (etcd's native lock service queues server-side, FIFO,
  requireLeader applied by jetcd); RW lock — watch nearest *conflicting* predecessor
  (set-emptiness predicate ⇒ no missed wakeups, herd-free); semaphore — prefix DELETE
  watch + full recheck (nearest-predecessor is provably wrong for rank admission).

## PR 1 — DistributedMutex

etcd's native lock RPC does the queuing. Acquisition: fresh `AcquisitionLease`
(keep-alive through the wait and the hold) → `lockClient.lock(path, leaseId)` →
deadline-bounded `get`. Attempt phase machine (WAITING/HOLDING/DEAD CAS) resolves the
fatal-vs-win race; timeout = cancel + **lease revoke** (deletes any just-granted key,
aborts the server-side wait); lease death mid-wait = fresh-lease retry after a 250 ms
pace (re-queues at the tail — documented fairness caveat). Lock-lost: once-guarded
dispossession, exceptions list, `LeaseEvent.Expired` → LOST, listeners, optional
interrupt.

## PR 2 — DistributedReadWriteLock

Hand-rolled: `read-`/`write-` entries under the lock path, each on its own
`AcquisitionLease`, ranked by createRevision (one sorted prefix GET = consistent
snapshot). Reader admitted iff no live earlier write entry (excluding the calling
thread's own write hold ⇒ write→read downgrade works); writer admitted iff no earlier
entry. Read→write upgrade throws (self-deadlock). **Fair/FIFO** semantics (prevents
writer starvation; Curator parity). Shared `WaiterSupport` wait-on-watch helper
(pre-live recheck + recovery recheck + Failed → throw + deadline), reused by PR 3.

## PR 3 — DistributedSemaphore

Canonical permit count CAS-created at `<path>/permits`, validated lazily on first use
(mismatch → typed error). Holder/waiter entries under `<path>/holders/`; admission =
createRevision rank ≤ permits (rank monotone while an entry lives ⇒ capacity provably
never exceeded). Counter-style API (any thread may release; LIFO among the instance's
holds), `PermitLostListener`, `withPermit { }`. Timeout/loss/release all funnel
through lease revoke.

## Testing

TDD; MockK for the lease observer + win-race unit tests; real-etcd suites with
out-of-band lease revocation for deterministic crash simulation; fault tests
(container pause > TTL) gated by `-PuseTestcontainers`. Per PR: `make lint` + full
`make tests-tc` before merge.
