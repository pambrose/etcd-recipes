# etcd-recipes — Product Enhancement Ideas (July 2026)

**Author:** Product review (technical PM perspective)
**Repo state reviewed:** `master` at 0.11.0 (post-#47)
**Positioning:** etcd-recipes aims to be for etcd what Apache Curator is for ZooKeeper — the
default JVM library for distributed coordination primitives. This review measures the current
recipe set against that ambition, against Curator's actual feature surface, and against what a
2026 Kotlin/JVM developer expects from a coordination library.

---

## Implementation status (updated 2026-07-16)

> **This review has been acted on.** All ten proposals were implemented between #51 and #82;
> the repo is now at 0.12.0. Each section below carries a **Status** block recording what
> landed, where it lives, and what didn't.
>
> **The prose of the original review is preserved as written** — every "What's missing"
> section describes the repo *as reviewed at 0.11.0*, not as it stands today. Read those
> sections as history; read the Status blocks for the current state.
>
> **One proposal is materially incomplete:** idea #9's `TreeCache` was never built (its
> `NodeCache` half shipped). Smaller outstanding items: batch dequeue (#4), the optional
> gRPC `NameResolver` bridge (#5), and a shared `AbstractCache` (#9).
>
> Several features landed under different names than proposed. See the
> [naming deviations](#naming-deviations) table before grepping for a proposed symbol.

---

## How difficulty is rated

| Rating | Rough effort | Meaning |
|---|---|---|
| **Low** | days–1 week | Composes existing extensions/infrastructure; low correctness risk |
| **Medium** | 1–3 weeks | New public API surface; meaningful design work; testable with existing harness |
| **High** | 3–6 weeks | Correctness-critical distributed-systems work; hard-to-test failure modes |
| **Very High** | 6+ weeks | Cross-cutting rework touching most of the library |

Effort assumes one experienced engineer familiar with the codebase, and includes tests
(thread-based + container-based where relevant), examples, and docs — the repo's own
contribution bar.

---

## Summary (ranked by product impact)

Status column added 2026-07-16; ✅ = delivered, ⚠️ = partially delivered.

| # | Enhancement | Why it matters | Difficulty | Status |
|---|---|---|---|---|
| 1 | Distributed lock suite (mutex, RW lock, semaphore) | Single biggest Curator-parity gap; locks are the #1 reason people adopt a coordination library | Medium–High | ✅ #58–60 |
| 2 | Coroutines-native API (`suspend` + `Flow`) | The library is 100% blocking today; Kotlin-first async is the key differentiator vs. raw jetcd | Medium–High | ✅ #62–64 |
| 3 | Connection resilience: retry policies, watch/lease recovery, connection-state listeners | Recipes silently degrade across etcd restarts/compaction; this is table stakes for production use | High | ✅ #51, #53–54 |
| 4 | Reliable queue semantics (at-least-once, delay queue, DLQ) | Current dequeue can lose messages on consumer crash; blocks real-work-queue adoption | High | ✅ #55–57 (no batch dequeue) |
| 5 | Watch-backed `ServiceProvider` with load-balancing strategies | Provider re-reads etcd on every call and only picks randomly; core discovery use case is underpowered | Low–Medium | ✅ #66 (no gRPC resolver) |
| 6 | Typed recipe layer (pluggable serialization) | Every recipe traffics in `ByteSequence`/`String`; typed payloads remove the largest source of user boilerplate | Medium | ✅ #76–78 |
| 7 | Observability: Micrometer metrics, health checks, structured error callbacks | No metrics anywhere; `exceptions` list is pull-only — invisible failures in production | Low–Medium | ✅ #69–75 |
| 8 | `LeaderLatch` + election ergonomics | Curator ships two election recipes for good reason; the callback-only `LeaderSelector` doesn't fit "hold leadership until shutdown" services | Low–Medium | ✅ #65 |
| 9 | `NodeCache` / `TreeCache` equivalents | Only prefix-children caching exists; single-key config watching is the most common cache need | Medium | ⚠️ #76 — **`TreeCache` not built** |
| 10 | Spring Boot starter (+ Ktor plugin) | Meets the largest JVM population where they live; converts evaluations into adoptions | Medium | ✅ #80 |

Honorable mentions that didn't make the cut are listed at the end.

---

## 1. Distributed lock suite: `InterProcessMutex`, read-write lock, semaphore

### What's missing
The README's own framing ("what Curator provides for ZooKeeper") sets an expectation the
library doesn't yet meet: **there is no lock recipe.** Curator's most-used recipes are
`InterProcessMutex`, `InterProcessReadWriteLock`, and `InterProcessSemaphoreV2`. Today
etcd-recipes offers only two thin pass-throughs in `common/LockExtensions.kt`:

```kotlin
fun Client.lock(keyName: String, leaseId: Long): LockResponse
fun Client.unlock(keyName: String): UnlockResponse
```

The caller must create and keep-alive their own lease, gets no reentrancy, no
`tryLock(timeout)`, no revocation awareness, and no protection against unlocking a lock they
don't hold. That's raw jetcd, not a recipe.

### Proposed scope
- **`DistributedMutex`** — `EtcdConnector`-based recipe owning its lease + keep-alive
  (the `DistributedBarrier`/`TransientKeyValue` pattern already does this). API:
  `lock()`, `tryLock(timeout)`, `unlock()`, `withLock { }` (Kotlin) /
  `Closeable` acquire (Java). Reentrancy via thread-local hold counts, mirroring Curator.
- **`DistributedReadWriteLock`** — writer-priority RW lock built on a key-scheme
  (`/lock/path/read-<rev>`, `/lock/path/write-<rev>`) with the same
  wait-on-predecessor watch pattern the queues already use.
- **`DistributedSemaphore`** — N permits as lease-bound child keys; `acquire()`,
  `tryAcquire(permits, timeout)`, `release()`.
- Fairness guarantee (FIFO by creation revision), documented lost-lock semantics (lease
  expiry → holder must observe revocation, e.g. a `lockLostListener`).

### Difficulty: **Medium–High** (~3–4 weeks phased)
- `DistributedMutex` alone is **Medium** (~1.5 weeks): etcd's native lock RPC (already exposed
  via jetcd's `lockClient`) does the hard queuing; the work is lease lifecycle, reentrancy,
  revocation callbacks, and the test matrix (contention, holder crash, etcd restart).
- RW lock and semaphore are the harder half (**High-ish**): no native etcd support, so the
  key-scheme and wake-ordering logic must be built and proven. The repo's existing
  strict-ordering queue work (#19/#46) shows exactly the class of subtle bugs to expect.
- Strong existing foundations: `EtcdConnector` lifecycle, `KeepAliveExtensions`,
  `TxnExtensions` CAS helpers, and the container-based test harness for true
  cross-process contention tests.

### Status: ✅ Shipped in full — #58 (mutex), #59 (RW lock), #60 (semaphore)

New `io.etcd.recipes.lock` package:
- `EtcdLock` — the shared interface: `lock()`, `tryLock(timeout)`, `unlock()`, `holdCount`,
  `addLockLostListener`/`removeLockLostListener`, plus `LockLostListener` and an inline
  `EtcdLock.withLock { }`.
- `DistributedMutex` — reentrant via per-thread hold counts, as proposed.
- `DistributedReadWriteLock` — per-side reentrancy, cooperative lock-lost.
- `DistributedSemaphore` — `acquire()`, `tryAcquire(timeout)`, `release()`,
  `availablePermits()`, `PermitLostListener`.
- Support: `AcquisitionLease`, `WaiterSupport`. Follow-up #67 anchored lock waiters'
  DELETE-watch at the observed revision to close a lost-wakeup hang.

Note: `DistributedSemaphore` does not implement `EtcdLock`, and its lost-permit callback is
`PermitLostListener` rather than a `LockLostListener`.

---

## 2. Coroutines-native API: `suspend` functions and `Flow`-based watches

### What's missing
`kotlinx-coroutines-core` is already a dependency, yet **there is not a single `suspend`
function in the library** — every operation blocks on `CompletableFuture.get()`, and blocking
primitives (`CountDownLatch`, `BooleanMonitor`) drive all waiting. For a Kotlin-first library
in 2026 this is the most visible product gap: a Ktor/coroutines-based service cannot call
`dequeue()` or `waitOnBarrier()` without dedicating a thread per waiter.

### Proposed scope
- `suspend` variants of the `common/` extension surface (`getValue`, `putValue`,
  `transaction`, lease grant/keep-alive) via `CompletableFuture.await()`.
- **`Flow<WatchEvent>` watch API**: `client.watchAsFlow(path, options)` with backpressure and
  cancellation → watcher close. This alone would make the library the nicest way to consume
  etcd watches on the JVM.
- Suspending recipe entry points: `DistributedQueue.receive()` (suspend),
  `DistributedBarrier.await()`, `LeaderSelector` leadership as a `Flow<LeadershipEvent>`,
  `PathChildrenCache.eventsAsFlow()`.
- Keep the existing blocking API intact for Java users; suspend variants live alongside
  (Curator solved the same problem with the separate `curator-x-async` module — here it can
  be same-artifact since Kotlin handles the dual surface naturally).

### Difficulty: **Medium–High** (~2–4 weeks)
- The mechanical part (future → suspend bridges) is **Low**.
- The real work is re-plumbing recipe *internals* that currently park threads on latches, and
  deciding cancellation semantics (what happens to a lock acquisition or dequeue CAS when the
  coroutine is cancelled mid-flight — the existing dequeue retry loop needs a cancellation
  audit).
- Watch-as-Flow must respect the existing Vert.x event-loop deadlock constraint
  (`WatchExtensions.kt` documents it well); `callbackFlow` with a buffered channel fits
  cleanly.
- Requires adding `kotlinx-coroutines-jdk8`/reactive test coverage but no new infra.

### Status: ✅ Shipped in full — #62 (foundation), #63 (suspend entry points), #64 (Flow surfaces)

The "not a single `suspend` function" finding above is obsolete: the new
`io.etcd.recipes.coroutines` package holds ~100 of them across 22 files.
- Watch-as-Flow: `Client.watchAsFlow(...): Flow<WatchFlowEvent>` and
  `Client.watchEventsAsFlow(...): Flow<WatchEvent>` (`WatchFlowEvent.kt`).
- Suspend RPC twins: `KVSuspend.kt`, `TxnSuspend.kt`, `LeaseSuspend.kt`, `RpcSuspend.kt`,
  `Bridges.kt`.
- Suspend recipe entry points: `AbstractQueue.receive()`/`receive(timeout)`,
  `DistributedBarrier.await()`, double-barrier `awaitEnter`/`awaitLeave`,
  `EtcdLock.withLock`, `WorkQueueSuspend.kt`.
- Flow event surfaces: `PathChildrenCache.eventsAsFlow()`, `NodeCache<T>.eventsAsFlow()`,
  `ServiceCache.eventsAsFlow()`, plus connection/lease/lock/exception flows.
- #63 also made the existing blocking waits interruptible, honoring the cancellation contract.

Deviation: leadership-as-Flow is `Client.leadershipAsFlow(...)` in `ElectionFlows.kt` (with
`LeadershipEvent.Elected/Vacated/WatchFailed`) — an extension on `Client`, not on
`LeaderSelector` as sketched above.

---

## 3. Connection resilience: retry policies, watch/lease recovery, connection-state listeners

### What's missing
Curator's second pillar (after recipes) is its **retry + connection-state machinery** — and
it's why people trust it in production. etcd-recipes currently has none of it:

- A watcher that dies (etcd restart, leader change, **compaction of the watched revision**)
  is not re-established; recipes like `PathChildrenCache` and `ServiceCache` silently go
  stale.
- Lease keep-alive failures land in the pull-only `exceptionList`; nothing attempts
  re-grant/re-register, so a `TransientKeyValue` or election candidacy can vanish
  permanently after a transient partition.
- No retry policy abstraction: every `.get()` on a gRPC future is one-shot.

### Proposed scope
- **`RetryPolicy`** (exponential backoff, bounded retries, forever) applied inside the
  `common/` extension layer so all recipes inherit it.
- **Resilient watcher**: track the last-seen revision, auto-resubscribe with
  `withRevision(lastSeen + 1)` on error/completion, and on `ErrCompacted` re-sync via a
  fresh GET + revision reset, emitting a `RESYNC` event to listeners.
- **Self-healing leases**: keep-alive wrapper that re-grants the lease and re-puts owned keys
  (with a `SessionExpiredEvent` surfaced to the recipe, since ownership may have been lost).
- **`ConnectionStateListener`** (CONNECTED / SUSPENDED / LOST / RECONNECTED) on
  `EtcdConnector`, so applications can react (e.g., a leader stepping down on LOST).

### Difficulty: **High** (~4–6 weeks)
- This is correctness-critical distributed-systems code with the hardest test story in the
  library: it needs fault-injection tests (Testcontainers etcd restarts/pauses — the
  container harness can do this with `docker pause`, but those tests don't exist yet).
- Recipe-by-recipe semantics decisions ("did I still hold the lock during the partition?")
  must each be made explicitly and documented.
- High leverage though: every existing and future recipe inherits it, and it's the
  prerequisite for credibly recommending the library for production. Consider shipping the
  resilient watcher first (~2 weeks) since caches/discovery benefit immediately.

### Status: ✅ Shipped in full — #51 (watchers), #54 (leases), #53 (RPC timeouts/retries)

The advice to ship the resilient watcher first was followed. All in `common/`:
- `RetryPolicy.kt` — `fun interface RetryPolicy` with `exponentialBackoff(...)`,
  `bounded(...)`, `forever`, `never`. Plus `ResilienceConfig.kt`, `RpcResilience.kt`,
  `RpcRetry.kt`.
- `WatchResilience.kt` — revision tracking, auto-resubscribe, `CompactedException` handling
  with resync past the compacted revision; `WatchRecoveryEvent.Suspended/Resubscribed/
  Resynced/Failed` + `WatchRecoveryListener`.
- `SelfHealingKeepAlive.kt` / `LeaseResilience.kt` — re-grant and re-establish on expiry;
  `LeaseEvent.Suspended/Expired/Restored/Failed` + `LeaseListener`.
- `ConnectionState.kt` — `enum ConnectionState { CONNECTED, SUSPENDED, RECONNECTED, LOST }`
  + `ConnectionStateListener`, wired into `EtcdConnector` (which maps watch and lease events
  onto state transitions) alongside `isHealthy()`. #54 also added leader step-down on loss.

**The fault-injection test gap called out above is closed.** `src/test/kotlin/io/etcd/recipes/fault/`
holds 12 specs driving real `EtcdTestContainer.pause()`/`unpause()`/`restart()` —
`ResilientWatcherFaultTests`, `SelfHealingLeaseFaultTests`, `CompactExtensionTests`,
`MutexFaultTests`, `SemaphoreFaultTests`, `ReadWriteLockFaultTests`, `LeaderLatchFaultTests`,
and more. Gated by `assumeFaultInjection()` (needs `-PuseTestcontainers`).

Deviation: there is no `SessionExpiredEvent` type — the equivalent is `LeaseEvent.Expired`.

---

## 4. Reliable queue semantics: at-least-once delivery, delay queue, dead-lettering

### What's missing
`AbstractQueue.dequeue()` is **at-most-once by construction**: the CAS transaction deletes
the item, *then* returns the value. A consumer that crashes between the delete and processing
loses the message. There's also no `poll(timeout)` (dequeue blocks forever), no bulk
operations, no delayed items, and no dead-letter handling. That limits the queues to
fire-and-forget use; anyone with real work-queue needs currently leaves for Kafka/SQS even
when their scale doesn't warrant it.

### Proposed scope
- **Claim-based consumption (at-least-once):** dequeue moves the item to a
  `/claimed/<consumer>` key bound to the consumer's lease instead of deleting. Consumer
  `ack()`s to delete, or the lease expires and a janitor (or next consumer) returns the item
  to the queue — a visibility-timeout model, cleanly expressible with existing
  `TxnExtensions` + lease infra.
- **`poll(timeout)` / non-blocking `tryDequeue()`** on the existing queues (small, ship
  first).
- **`DistributedDelayQueue`** — items keyed by ready-timestamp; consumers watch the head and
  sleep until it matures (Curator has this; etcd's sorted key range makes it natural).
- **Dead-letter path + max-redelivery count** stored in item metadata.
- **Batch enqueue/dequeue** via a single txn for throughput.

### Difficulty: **High** (~3–5 weeks for the full set)
- `poll(timeout)`/`tryDequeue` is **Low** (days) — the watcher/latch plumbing already exists.
- The claim/ack model is the hard core (**High**): redelivery races, janitor election (can
  reuse `LeaderSelector`!), and ordering interactions with the strict-priority guarantee that
  was just carefully restored in #46. Container-based crash tests are essential and the
  harness supports them.
- Delay queue is **Medium** once claims exist.
- Recommend versioning this as a new `DistributedWorkQueue` recipe rather than changing
  `DistributedQueue` semantics under existing users.

### Status: ✅ Shipped — #55 (poll/tryDequeue/enqueueAll), #56 (work queue + DLQ), #57 (delayed delivery)

The "new recipe rather than changed semantics" recommendation was taken:
`queue/DistributedWorkQueue.kt` with `WorkQueueConfig(visibilityTimeoutSecs = 30, maxDeliveries, ...)`.
- **Claim-based at-least-once** — `claimed/` path, `receive()`/`receive(timeout)`/`tryReceive()`
  returning a `WorkItem` with `ack()` and `requeue()`.
- **Janitor** — `reclaimOrphans()` on a scheduled sweep, plus opportunistic reclaim on receive.
- **Dead-lettering** — `dlq/` path, `deadLetters()`, `requeueDeadLetter(id)`,
  `purgeDeadLetter(id)`, with an `attemptsPath` + `maxDeliveries` redelivery cap.
- **`poll(timeout)` / `tryDequeue()`** — on `AbstractQueue`, so the existing queues get them.
- Follow-up #68 anchored the queue waiter's watch to close a lost-wakeup race.

**Outstanding:**
- **Batch dequeue.** `enqueueAll(values)` shipped as a single all-or-nothing txn on
  `DistributedWorkQueue`, `DistributedQueue`, and `TypedDistributedQueue`, but there is no
  `dequeueAll`/`receiveAll` counterpart. The proposal asked for batch enqueue *and* dequeue.

Deviation: there is no standalone `DistributedDelayQueue` class. Delayed delivery folded into
the work queue as `enqueue(value, delay: Duration)` — a `delayed/` path keyed by `readyAt`
timestamp, swept into `items/` on maturity. Consistent with the recommendation above not to
proliferate queue recipes.

---

## 5. Watch-backed `ServiceProvider` with load-balancing strategies

### What's missing
`ServiceProvider` is currently ~20 lines: `getInstance()` does a full etcd range read of all
instances **on every call** and picks one at random. There is no caching, no strategy choice,
no notion of an unhealthy instance, and no integration with the already-existing
`ServiceCache` (which maintains exactly the watch-backed instance map the provider should be
reading from). Curator's `ServiceProvider` offers pluggable `ProviderStrategy`
(round-robin, random, sticky) plus `noteError()` down-instance tracking — that's the feature
people actually build client-side load balancing on.

### Proposed scope
- Back `ServiceProvider` with a `ServiceCache` so `getInstance()` is an in-memory read
  (watch-updated), with lifecycle owned by the provider (`start()`/`close()`).
- **Pluggable `ProviderStrategy`**: `RoundRobin`, `Random`, `Sticky` (session affinity),
  `WeightedResponseTime` later. Strategy is a small public interface — easy community
  contribution surface.
- **`noteError(instance)` + down-instance policy**: error threshold + timeout window before
  an instance is eligible again.
- Optional: an `io.grpc.NameResolver` bridge so gRPC clients can target
  `etcd:///serviceName` directly — a compelling, demo-able integration for the etcd audience.

### Difficulty: **Low–Medium** (~1–2 weeks; the gRPC resolver adds ~1 week)
- All the hard infrastructure (watch-backed `ServiceCache`, JSON `ServiceInstance`
  round-tripping) already exists; this is composition plus a small strategy interface.
- Main design decision is provider lifecycle (it just became lifecycle-free in a recent fix —
  reintroducing owned resources needs the `EtcdConnector` treatment).
- High demo value relative to cost: best effort-to-impact ratio on this list.

### Status: ✅ Shipped (core scope) — #66

`discovery/ServiceProvider.kt` is now cache-backed: `start()` creates and owns a
`ServiceCache`, `doClose()` closes it, and `getInstance()` is an in-memory read.
- `discovery/ProviderStrategy.kt` — `fun interface ProviderStrategy`, with `RandomStrategy`
  (the default), `RoundRobinStrategy`, and `StickyStrategy(delegate)`. `WeightedResponseTime`
  was explicitly "later" and is not built.
- `noteError(instance)` + down-instance policy — `errorThreshold` / `downPeriod` constructor
  params driving private `isDown`/`availableInstances` ejection.

**Outstanding:**
- **The `io.grpc.NameResolver` bridge.** Not built — no `NameResolver` reference anywhere, and
  gRPC is not a declared dependency (it arrives only transitively via jetcd). This was marked
  *Optional* and separately priced (~1 week) in the proposal, so the core of #5 is complete.
  Still the most demo-able unclaimed item on this list.

---

## 6. Typed recipe layer: pluggable serialization across recipes

### What's missing
Every recipe's payload is stringly/byte-typed: queues traffic in `ByteSequence`,
`TransientKeyValue` and caches in `String`/`ByteSequence`, and `ServiceInstance` carries an
opaque `var jsonPayload: String` the user must encode/decode by hand. Meanwhile
`kotlinx-serialization-json` is already on the classpath. Every real application writes the
same marshalling boilerplate — and gets runtime surprises where a typed API would give
compile-time safety. (Curator grew `ModeledFramework` for exactly this reason.)

### Proposed scope
- A tiny **`EtcdCodec<T>`** interface (`encode(T): ByteSequence`, `decode(ByteSequence): T`)
  with built-ins: `StringCodec`, `KotlinxJsonCodec<T>` (reified helpers), and a
  `JacksonCodec<T>` adapter in a separate optional module for Java users.
- Generic recipe variants: `DistributedQueue<T>`, `TransientKeyValue<T>`,
  `PathChildrenCache<T>` (typed `ChildData<T>`), `ServiceInstance<T>` with a typed
  `payload: T` (keeping the JSON-string form as the wire format for compatibility).
- Typed KV extensions: `client.putValue(path, value, codec)` /
  `client.getValue<T>(path)`.

### Difficulty: **Medium** (~2–3 weeks)
- The codec interface and typed extensions are **Low**.
- The cost is generifying existing public recipe classes **without breaking current users** —
  likely via new generic classes with the existing ones as `ByteSequence`/`String`
  specializations, plus deprecation guidance. Wire-format compatibility for
  `ServiceInstance` needs care (existing registrations must still parse).
- No new distributed-systems risk — this is API design work, well covered by existing tests.

### Status: ✅ Shipped in full — #76 (codec foundation), #77 (typed KV + Jackson), #78 (typed recipes)

- `common/EtcdCodec.kt` — `interface EtcdCodec<T>` (`encode`/`decode`) with `StringCodec`,
  `ByteSequenceCodec` (bonus), `KotlinxJsonCodec<T>`, and a reified `jsonCodec<T>()` helper.
- `etcd-recipes-jackson` — a separate optional module holding `JacksonCodec<T>` /
  `jacksonCodec<T>()`, exactly as proposed for Java users.
- `common/TypedKVExtensions.kt` — `Client.putValue(...)` / `Client.getValue(...)` over a codec.
- Typed recipes: `TypedDistributedQueue<T>`, `TypedDistributedPriorityQueue<T>` (bonus),
  `TypedTransientKeyValue<T>`, `TypedPathChildrenCache<T>` + `TypedChildData<T>`.

The compatibility strategy above was honored: new `Typed*` classes sit alongside the existing
ones rather than generifying them, so no current user breaks.

Deviation: there is no generic `ServiceInstance<T>` class. Typed payloads are extensions on
the existing non-generic `ServiceInstance` — `payload(codec)`, `setPayload(...)`,
`serviceInstance(...)` in `discovery/TypedServiceInstance.kt`, decoding the existing
`jsonPayload` wire format. This preserves wire compatibility, which was the stated concern.

---

## 7. Observability: Micrometer metrics, health checks, push-based error callbacks

### What's missing
The library has **zero metrics** and its only failure-surfacing mechanism is the pull-only
`EtcdConnector.exceptions` list — if the application doesn't poll it, background failures
(keep-alive death, watcher errors) are invisible until behavior gets weird. For a library
whose recipes hold locks and leadership, that's a production-readiness gap that shows up in
every serious evaluation checklist.

### Proposed scope
- **Push-based error/lifecycle listener** on `EtcdConnector`:
  `onBackgroundException { }`, plus recipe lifecycle events. Keep `exceptions` for
  compatibility. (Cheapest, highest-value item on this whole list — ~2 days.)
- **Micrometer instrumentation** (optional dependency, no-op without it): lock/queue wait
  time and hold time, election leadership transitions + current-leader gauge, queue depth,
  cache sync latency, keep-alive failures/renewals, watcher resubscribes.
- **Health contribution**: `isHealthy()` on `EtcdConnector` (client reachable, lease alive,
  watcher live) — feeds directly into idea #10's Spring health indicator.
- Structured logging pass: consistent MDC keys (recipe path, clientId) across recipes.

### Difficulty: **Low–Medium** (~1–2 weeks)
- Micrometer's API makes optional instrumentation straightforward; the metric *points* are
  easy because recipes already funnel through `EtcdConnector` and the `common/` extensions.
- No breaking changes; nearly all additive. Main effort is choosing stable metric names
  (they become public API) and adding assertions to existing tests.

### Status: ✅ Shipped in full — #69–#75, delivered in phases

- **Push-based error listener** (#69) — `common/BackgroundExceptionListener.kt`
  (`fun interface BackgroundExceptionListener` + `BackgroundException`), wired onto
  `EtcdConnector` via `addBackgroundExceptionListener`. `exceptions` kept for compatibility.
  Same PR added `isHealthy()` and a `ping()` probe.
- **Metrics SPI** (#70) — `common/EtcdMetrics.kt`, an `interface EtcdMetrics` with an
  `EtcdMetrics.NoOp` default, covering every proposed point: `recordLockWait`,
  `recordLockHold`, `incrementLeadershipTransition`, `recordQueue`, `recordCacheSync`,
  `incrementKeepAlive`, `incrementWatchRecovery`, `recordRpc`. Instrumented at the RPC, watch,
  and lease funnels, then across locks/election (#72) and queues/caches (#73).
- **Micrometer binding** (#71) — `etcd-recipes-micrometer`, a separate module
  (`MicrometerEtcdMetrics`, `EtcdGauges`) with meters `etcd.rpc`, `etcd.lock.wait`,
  `etcd.lock.hold`, `etcd.queue`, `etcd.cache.sync`, `etcd.cache.size`. Live gauges (#74):
  `bindQueueDepth`, `bindCacheSize`, `bindServiceCacheSize`, `bindAvailablePermits`,
  `bindLeadership`. The no-op-without-it requirement holds — core declares no Micrometer dep.
- **MDC** (#75) — `RECIPE_MDC_KEY` + `withRecipeLoggingContext { }`, carried across recipe
  background threads.
- Bonus beyond scope: `backgroundExceptionsAsFlow()` and `connectionStateAsFlow()`.

Deviations: the callback is `BackgroundExceptionListener.onException`, not
`onBackgroundException`; MDC uses a single `etcd.recipe` key rather than the proposed set
(recipe path, clientId).

---

## 8. `LeaderLatch` and election ergonomics

### What's missing
The single election recipe, `LeaderSelector` (482 lines), is callback-scoped: you get
leadership inside `takeLeadershipBlock` and lose it when the block returns. Curator ships a
second recipe, **`LeaderLatch`**, for the other (arguably more common) shape: *acquire
leadership and hold it until process shutdown*, with `await()` and
`isLeader`/`notLeader` listeners. Building that shape on today's `LeaderSelector` means a
block that parks a thread forever — awkward and error-prone. There's also no way to observe
an election (who leads, when it changes) without being a candidate.

### Proposed scope
- **`LeaderLatch`**: `start()`, `await()`/`await(timeout)`, `hasLeadership`,
  `addListener(isLeader/notLeader)`, `close()` releases candidacy. Internally reuses
  `LeaderSelector`'s key-scheme so latches and selectors interoperate in one election.
- **Election observer**: `LeaderObserver(electionPath)` — watch-backed current-leader value +
  change listener, no candidacy (today's `LeaderReporter` util is a sketch of this;
  productize it).
- `Flow<LeadershipEvent>` variant once idea #2 lands.

### Difficulty: **Low–Medium** (~1–2 weeks)
- The election mechanics (lease-bound candidacy keys, watch-predecessor) already exist and
  were hardened in `LeaderSelector`; this is mostly a new state-machine wrapper + listener
  plumbing.
- Test harness fits perfectly (thread-based + container-based election tests already exist
  as patterns to copy).
- Correct `notLeader` delivery on lease loss depends partly on idea #3's session-loss
  detection — deliver best-effort now, tighten later.

### Status: ✅ Shipped in full — #65

- `election/LeaderLatch.kt` — `class LeaderLatch : EtcdConnector` with `start()`, `await()`,
  `await(timeout)`, `hasLeadership`, `addListener`/`removeListener`, `close()`, plus a
  `withLeaderLatch { }` scope function. `LeaderLatchListener { isLeader(); notLeader() }`.
- `election/LeaderObserver.kt` — the observer productized as proposed: watch-only, with
  `currentLeader`, `addListener(LeaderListener)`, and recovery-aware re-read. The old
  `util/LeaderReporter.kt` sketch remains as a `main()` demo, not a competing API.
- `Flow<LeadershipEvent>` — landed with #64 (`coroutines/ElectionFlows.kt`).

The dependency noted above resolved favorably: #3 shipped first, so `notLeader` delivery on
lease loss rests on real session-loss detection rather than the best-effort fallback.
`LeaderLatchFaultTests` exercises it under `docker pause`.

---

## 9. `NodeCache` / `TreeCache` equivalents

### What's missing
`PathChildrenCache` covers one shape: the immediate children of a prefix. The two other cache
shapes people constantly need are missing:
- **Single-key cache (`NodeCache`)** — "keep this one config value hot, tell me when it
  changes." Today users hand-roll a watcher + initial GET and inevitably get the
  race between them wrong (the queue code's own comments document how subtle that gap is).
- **Subtree cache (`TreeCache`)** — a consistent, watch-updated map of an entire prefix
  (arbitrary depth), the natural backing for config trees and routing tables.

### Proposed scope
- **`NodeCache`**: initial GET + watch from `revision + 1` (atomic bootstrap — no
  missed/duplicated events), current-value accessor, change listeners, `waitOnInitialLoad()`.
- **`TreeCache`**: same bootstrap pattern over a prefix range read; exposes an immutable
  snapshot map + PUT/UPDATE/DELETE/INITIALIZED listener events.
- Align listener/event vocabulary with `PathChildrenCache` (possibly extract a shared
  `AbstractCache`), and document the consistency model (eventually consistent, revision-gap
  free).

### Difficulty: **Medium** (~2 weeks)
- The revision-atomic bootstrap (GET at revision R, watch from R+1) is the one subtle piece —
  and it's also the building block idea #3 needs, so this work compounds.
- `NodeCache` is small (**Low**); `TreeCache`'s memory model and event ordering under
  concurrent updates need care (**Medium**).
- Without idea #3, both caches share `PathChildrenCache`'s existing limitation (a dead
  watcher goes silently stale) — worth shipping anyway, but note the dependency.

### Status: ⚠️ Partially shipped — `NodeCache` landed in #76; **`TreeCache` was never built**

This is the one proposal on the list with material scope outstanding. The split fell along the
difficulty line the section itself drew: the **Low**-rated half shipped, the **Medium**-rated
half did not.

**Shipped — `NodeCache<T>`** (`cache/NodeCache.kt`, generic over an `EtcdCodec`, with
`withNodeCache`):
- Revision-atomic bootstrap as specified — `reconcile()` GETs and returns
  `resp.header.revision + 1`; `start()` anchors the watch at that revision.
- `current: T?` / `currentBytes: ByteSequence?` accessors, `addListener(NodeCacheListener<T>)`,
  `addRecoveryListener`, and `NodeCacheEvent<T>` with `Type { CREATED, UPDATED, DELETED }`.
- The #3 dependency noted above never bit: resilience shipped first, so `NodeCache` is
  recovery-aware from day one rather than inheriting the silently-stale limitation.

**Outstanding:**
- **`TreeCache` — not built.** No arbitrary-depth prefix cache, no immutable snapshot map, no
  tree-level `PUT/UPDATE/DELETE/INITIALIZED` events. The name appears only in this document.
  Config trees and routing tables remain unserved.
- **No shared `AbstractCache`.** `NodeCache` and `PathChildrenCache` each extend
  `EtcdConnector` directly and duplicate the reconcile/watch/listener pattern. `AbstractQueue`
  shows the precedent. Worth extracting *before* `TreeCache` is written, so the third cache
  amortizes it rather than triplicating it.
- **No `NodeCache.waitOnInitialLoad()`** — arguably moot, since `start()` loads synchronously
  before returning. (`PathChildrenCache` has `waitOnStartComplete()`.) `NodeCacheEvent` also
  has no `INITIALIZED` type.

Vocabulary note: the alignment goal is unmet in one respect — `PathChildrenCacheEvent.Type` is
`CHILD_ADDED/CHILD_UPDATED/CHILD_REMOVED/INITIALIZED` while `NodeCacheEvent.Type` is
`CREATED/UPDATED/DELETED`. A `TreeCache` would need to pick one.

---

## 10. Spring Boot starter (+ Ktor plugin)

### What's missing
Adoption packaging. The library is on Maven Central with good docs, but a Spring Boot shop —
still the largest JVM population — has to hand-wire `Client` lifecycle, recipe beans, and
shutdown ordering. A `spring-boot-starter-etcd-recipes` converts a 30-minute integration into
a 3-line `application.yml` entry, and the starter's existence is itself marketing (starters
are how Spring developers discover libraries). A lightweight Ktor plugin covers the
Kotlin-native side, which is this library's natural audience.

### Proposed scope
- **`etcd-recipes-spring-boot-starter`** (new Gradle module): auto-configured `Client` from
  `etcd.recipes.*` properties (endpoints, auth user/password, TLS, namespace, timeouts),
  graceful shutdown ordering, `HealthIndicator` (from idea #7), and prototype-scoped
  factories for recipes. `@ConditionalOnClass` so it stays optional.
- **`etcd-recipes-ktor`**: Ktor server plugin exposing the client + recipes with
  application-lifecycle hooks; pairs naturally with idea #2's suspend API.
- First-class **auth/TLS convenience** in `connectToEtcd` itself (today's escape hatch is
  "know the jetcd `ClientBuilder` API") — this piece benefits every user, not just Spring's.

### Difficulty: **Medium** (~2–3 weeks)
- Well-trodden pattern, low distributed-systems risk; the multi-module build already exists
  to copy from (`etcd-recipes-examples`, `etcd-recipes-test-runners`).
- Costs: a Spring dependency surface to keep current (Boot 3.x baseline), starter integration
  tests, and version-matrix documentation. The Ktor plugin should wait for idea #2 to be
  genuinely idiomatic.

### Status: ✅ Shipped in full — #80

The sequencing advice held: #2 landed well before the Ktor plugin, so it sits on a genuinely
idiomatic suspend API.

- **`etcd-recipes-spring-boot-starter`** — `EtcdProperties` (endpoints, user, password,
  namespace, connectTimeout, retryMaxDuration, nested `Tls`), `EtcdAutoConfiguration`
  (`@ConditionalOnClass(Client::class)`, `@Bean(destroyMethod = "close")` for shutdown
  ordering, all `@ConditionalOnMissingBean`), and `EtcdHealthIndicator` delegating to
  `client.ping()` — guarded by `@ConditionalOnClass(HealthIndicator::class)` with Actuator as
  `compileOnly`. Registered via `META-INF/spring/…AutoConfiguration.imports`.
- **`etcd-recipes-ktor`** — `EtcdPlugin`, `EtcdPluginConfig`, and `Application.etcdClient` /
  `Application.etcdRecipes` accessors.
- **Auth/TLS/namespace convenience in `connectToEtcd`** — `common/EtcdConnectionConfig.kt`
  (`EtcdConnectionConfig` + `EtcdTlsConfig`), applied by `ClientBuilder.applyConfig(config)`.
  Both `connectToEtcd(urls, initReceiver)` and `connectToEtcd(config, initReceiver)` exist, so
  the jetcd `ClientBuilder` escape hatch is no longer required. This also delivers the
  **namespace support** honorable mention.

Deviation: recipes are exposed through a single `EtcdRecipes` factory class
(`common/EtcdRecipes.kt` — `mutex`, `readWriteLock`, `semaphore`, `distributedQueue`,
`distributedPriorityQueue`, `leaderLatch`, `pathChildrenCache`, `nodeCache<T>`,
`serviceDiscovery`, `distributedAtomicLong`) surfaced as one bean, rather than
prototype-scoped per-recipe factory beans.

---

## Naming deviations

Several features shipped under different names than proposed. Grep for the right-hand column.

| Proposed | Shipped as |
|---|---|
| `DistributedDelayQueue` (#4) | folded into `DistributedWorkQueue.enqueue(value, delay)` |
| `SessionExpiredEvent` (#3) | `LeaseEvent.Expired` |
| `onBackgroundException { }` (#7) | `BackgroundExceptionListener.onException` |
| `ServiceInstance<T>` (#6) | extensions on non-generic `ServiceInstance` (`TypedServiceInstance.kt`) |
| `DistributedQueue<T>`, `PathChildrenCache<T>`, … (#6) | `Typed`-prefixed siblings (`TypedDistributedQueue<T>`) |
| `LeaderSelector` leadership `Flow` (#2) | `Client.leadershipAsFlow(...)` |
| Per-recipe Spring factory beans (#10) | one `EtcdRecipes` factory bean |

---

## Honorable mentions

- ✅ **`poll(timeout)` on queues** — called out inside idea #4, but worth shipping alone
  immediately; days of work, removes the "dequeue blocks forever" footgun.
  *Shipped in #55, on `AbstractQueue`.*
- ✅ **Push-based exception callback** — likewise separable from idea #7; ~2 days.
  *Shipped in #69 as `BackgroundExceptionListener`.*
- ✅ **Namespace support** — jetcd supports key namespacing; surfacing it in `connectToEtcd`
  gives every recipe multi-tenancy nearly for free (Low).
  *Shipped in #80 as `EtcdConnectionConfig.namespace`.*
- ⬜ **Distributed ID generator** — block-allocating sequence IDs on top of
  `DistributedAtomicLong` (Low–Medium). *Not built.*
- ⬜ **`SharedValue` / `SharedCount`** — watchable shared value with CAS `trySetValue`,
  a small Curator-parity item (Low). *Not built.*
- ⬜ **GraalVM native-image metadata** — reachability metadata for jetcd/gRPC is nontrivial but
  increasingly asked for by Ktor/Quarkus users (Medium). *Not built.*
- ⬜ **Admin CLI** — `ShowKeys`/`LeaderReporter` utils grown into a small published CLI
  (watch a prefix, inspect an election, drain a queue); useful for demos and debugging
  (Low–Medium). *Not built; the utils remain `main()` demos.*
- ⬜ **BOM artifact** — once the starter/Ktor/Jackson-codec modules exist, publish a BOM to keep
  user version alignment trivial (Low). *Not built — but the precondition is now met: the
  build has four satellite modules (micrometer, jackson, spring-boot-starter, ktor).*

## Suggested sequencing

> **Retrospective (2026-07-16):** this ordering was broadly followed, and the two
> dependency calls in it paid off. #3 shipped before #4's promotion, and before both #8 and
> #9 — so `LeaderLatch`'s `notLeader` delivery and `NodeCache`'s staleness both got the real
> resilience story instead of the best-effort fallbacks hedged for below. The revision-atomic
> bootstrap was indeed built once and reused. The one deviation: #9 was only half-built.

If capacity forces an order: **5 → 7 → 8** are quick wins that visibly round out the product
in one release; **1 (mutex first) and 2** are the flagship features worth headlining a 0.12/0.13
release each; **3** unlocks the production-hardening story and should precede heavy promotion
of **4**; **6, 9, 10** slot in as parallelizable mid-size efforts. Ideas #3 and #9 share the
revision-atomic watch bootstrap — build that primitive once, early.

---

## What's next

Everything still open from this review, smallest first:

1. **Shared `AbstractCache`** (#9) — extract before writing `TreeCache`, not after.
2. **Batch dequeue** (#4) — `dequeueAll`/`receiveAll` to match `enqueueAll`.
3. **`TreeCache`** (#9) — the only material gap; **Medium**, ~2 weeks as originally rated.
4. **gRPC `NameResolver` bridge** (#5) — optional, ~1 week, highest demo value of the four.
5. Unclaimed honorable mentions: `SharedValue`/`SharedCount`, ID generator, BOM, GraalVM
   metadata, admin CLI.
