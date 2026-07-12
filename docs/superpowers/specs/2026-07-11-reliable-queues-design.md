# Reliable Queue Semantics — Design (product idea #4)

Approved 2026-07-11. Three sequential PRs, each merged before the next starts.

## PR A — poll / tryDequeue / batch enqueue (existing queues)

- `tryDequeue(): ByteSequence?` — non-blocking CAS-take of the head, null when empty.
- `poll(timeout): ByteSequence?` (Duration + Long/TimeUnit overloads) — bounded wait,
  null on timeout. `dequeue()` becomes the wait-forever case of the same internal
  loop; its signature and the DesignFixesTests source invariants (`dequeue()` exactly
  once, `while (true)` present) are preserved.
- `enqueueAll(values)` on `DistributedQueue` — one transaction; entries share a
  revision and preserve argument order. Priority queue excluded (per-item CAS
  sequencing makes batched slots a poor fit).

## PR B — DistributedWorkQueue (at-least-once, DLQ)

New recipe; existing queues keep at-most-once semantics untouched. Payloads are
never bound to the consumer's lease — only the claim marker is:

| Key | Content | Leased |
|---|---|---|
| `items/<k>` | pending payload (time-ordered key) | no |
| `claimed/<k>` | in-flight payload copy | no (survives crashes) |
| `claims/<k>` | consumer id | yes — TTL = visibility timeout |
| `attempts/<k>` | delivery count | no |
| `dlq/<k>` | dead-lettered payload | no |

- `receive()` claims the head atomically (txn guarded on `items/<k>` mod-revision):
  delete item, copy to `claimed`, put leased `claims` marker, bump `attempts`.
  Returns `WorkItem(value, id, attempt)` with `ack()` (txn-deletes all three keys,
  guarded on the claim still being ours; returns false if the claim was lost — the
  documented at-least-once window) and `requeue()` (early nack, attempts kept).
- Consumer crash ⇒ lease expiry ⇒ `claims/<k>` vanishes, `claimed/<k>` survives.
  Consumer-driven reclaim (no elected janitor): every consumer sweeps on
  empty-queue waits plus a jittered interval; orphans CAS-move back to `items/<k>`
  (same key ⇒ original FIFO position) or to `dlq/<k>` once `attempts >= maxDeliveries`.
- One plain (deliberately NOT self-healing) lease per consumer instance — expiry
  must release claims. After lease loss the instance grants a fresh lease for
  future claims and surfaces the event via lease listeners / connection state.
- `WorkQueueConfig(visibilityTimeoutSecs, maxDeliveries, sweepInterval)`; DLQ
  inspection/requeue/purge. Empty-queue wait reuses the resilient-watcher pattern
  via a helper shared with `AbstractQueue`.

## PR C — delayed delivery (on the work queue)

- `enqueue(value, delay)` writes `delayed/<readyTimestampMillis>-<uniq>`.
- Sweeps and empty-queue waits promote matured items into `items/` via CAS (one
  winner); waits sleep no longer than the head's maturity. Client-clock skew is
  documented; tolerable at visibility-timeout scale. No standalone delay queue.

## Decisions (user-approved)

- Three sequential PRs; consumer-driven reclaim; batch enqueue only.
- Testing: TDD; MockK unit suites for claim/ack/reclaim/DLQ transitions; real-etcd
  tests using out-of-band lease revocation to simulate consumer crashes; fault
  tests (pause > visibility timeout, restart mid-receive). README/CHANGELOG/
  examples per phase; `make lint` + full `make tests-tc` gate per PR.
