# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Connection-resilience release: watchers survive fatal stream deaths (part 1),
leases self-heal and leaders step down on lease loss (part 2), and blocking RPCs
gain timeouts and retries (part 3). Plus reliable-queue groundwork: bounded and
non-blocking consumption on the existing queues.

### Added (observability: push errors + health)

- **Push-based background-exception callback** on `EtcdConnector`: register a
  `BackgroundExceptionListener` (`addBackgroundExceptionListener` /
  `removeBackgroundExceptionListener`) to be notified — with a short source context
  (the recipe's path/clientId) — the moment a background failure occurs (keep-alive
  death, abandoned watcher, lost lock/leadership, a throwing user callback), instead
  of polling the `exceptions` list. Every recipe now routes its failures through a
  single `recordException` sink; the pull-only `exceptions` API is unchanged.
- **Health** on `EtcdConnector`: `isHealthy()` (passive — healthy unless a lease
  expired / watcher was abandoned, or the connector is closed) and `ping()` (an
  active, bounded, non-mutating reachability probe).
- Coroutines: `EtcdConnector.backgroundExceptionsAsFlow()` surfaces the same
  notifications as a `Flow<BackgroundException>`.

### Added (observability: metrics SPI)

- `EtcdMetrics` — a dependency-free instrumentation SPI. Install a backend with
  `ResilienceConfig.withMetrics(metrics)` (bring your own, or a Micrometer binding in a
  later release); the default (`EtcdMetrics.NoOp`) records nothing, so off means zero
  overhead. The three connection funnels every recipe shares are instrumented: blocking
  RPC latency / attempts / outcome (`recordRpc`), resilient-watcher recovery transitions
  (`incrementWatchRecovery`), and self-healing-lease events (`incrementKeepAlive`).

### Added (observability: queue + cache metrics)

- More recipe metric seams via the `EtcdMetrics` SPI: the queues record dequeue latency
  (`recordQueue`, measured call→item-in-hand), and `PathChildrenCache` / `ServiceCache` record
  each snapshot (re)sync (`recordCacheSync`, with the resulting entry count). The Micrometer
  binding maps these to an `etcd.queue` timer and an `etcd.cache.sync` timer plus an
  `etcd.cache.size` distribution.

### Added (observability: lock + election metrics)

- Recipe-level metric seams via the `EtcdMetrics` SPI: `DistributedMutex`,
  `DistributedReadWriteLock`, and `DistributedSemaphore` record acquisition wait time
  (`recordLockWait`, with the acquired/timed-out outcome) and hold time (`recordLockHold`);
  `LeaderSelector` (and so `LeaderLatch`, which composes it) records leadership take/relinquish
  transitions (`incrementLeadershipTransition`). The Micrometer binding maps these to
  `etcd.lock.wait` / `etcd.lock.hold` timers and an `etcd.election.transitions` counter.

### Added (observability: Micrometer binding)

- New **`etcd-recipes-micrometer`** module: `MicrometerEtcdMetrics(registry)` is a
  Micrometer backend for the `EtcdMetrics` SPI. Install it with
  `ResilienceConfig.withMetrics(MicrometerEtcdMetrics(registry))` to record `etcd.rpc`
  (a timer plus a retry counter, tagged by operation and outcome), `etcd.watch.recovery`,
  and `etcd.keepalive` (counters tagged by kind), with tag cardinality kept low. Micrometer
  is an `api` dependency of this module only — the core library stays dependency-free.

### Fixed (locks: read-write lock / semaphore wait)

- `DistributedReadWriteLock` and `DistributedSemaphore` could park a caller
  indefinitely under contention. A waiter's DELETE-watch was created without a
  start revision, so it began at whatever revision etcd assigned when it processed
  the create — racing the pre-live recheck GET. A predecessor's release landing in
  that watch-establishment window was missed by both the watch and the recheck, and
  an unbounded `lock()` then parked forever. Each wait now anchors its watch at the
  revision where its ranged read observed the blocker present, so the release is
  always (re)delivered; the pre-live recheck is now only a fast path.

### Fixed (barriers / queue: waiter watch)

- `DistributedBarrier`, `DistributedBarrierWithCount`, and the queues
  (`DistributedQueue` / `DistributedPriorityQueue`) shared the same un-anchored-watch
  race as the locks: a waiter's watch was subscribed without a start revision, so a
  barrier DELETE / ready DELETE / queue PUT landing in the watch-establishment window
  could be lost by both the watch and the pre-live recheck. Each now anchors its watch
  at the revision its pre-subscribe read observed, so the awaited event is always
  (re)delivered.

### Added (discovery: load-balancing ServiceProvider)

- `ServiceProvider` now extends `EtcdConnector` and owns an internal `ServiceCache`:
  `start()` backs reads with a watch-updated in-memory instance map (`close()` releases
  it); without `start()` each read does a direct etcd lookup, so the existing 3-arg
  constructor and `getAllInstances()`/`getInstance()` behavior is unchanged.
- Pluggable `ProviderStrategy` (SAM): `RandomStrategy` (default), `RoundRobinStrategy`,
  `StickyStrategy` (session affinity).
- `noteError(instance)` ejects a failing instance from selection after an error
  threshold for a down window, then auto-recovers (keyed by instance value, not the
  unstable `id`).
- `ServiceDiscovery.serviceProvider(name, strategy, …)` overload + `withServiceProvider { }`.

### Added (election: leader latch)

- `LeaderLatch` — a Curator-style leader latch that acquires leadership and **holds
  it until `close()`**, unlike the callback-scoped `LeaderSelector`. Query
  `hasLeadership`, block on `await()` / `await(timeout)`, or register a
  `LeaderLatchListener` (`isLeader`/`notLeader`). Composes a `LeaderSelector` per
  leadership term (via a worker term-loop), so latches and selectors interoperate in
  the same election; on lease loss it steps down, fires `notLeader`, and re-contests.
  Plus a `withLeaderLatch { }` DSL.
- `LeaderObserver` — a blocking/Java election observer (no candidacy): a
  `currentLeader` snapshot and `LeaderListener` take/relinquish callbacks, backed by
  the resilient watcher. The counterpart to the coroutine `Client.leadershipAsFlow`.
- Internal `ElectionPaths` helper unifies the election key scheme; the duplicate in
  the coroutine `leadershipAsFlow` now shares it.

### Added (coroutines: event flows)

- Recipe event streams exposed as `Flow`s in `io.etcd.recipes.coroutines`:
  `PathChildrenCache.eventsAsFlow()` / `recoveryEventsAsFlow()`,
  `ServiceCache.eventsAsFlow()` / `recoveryEventsAsFlow()`,
  `EtcdConnector.connectionStateAsFlow()` (emits current state, conflated),
  `leaseEventsAsFlow()` on `TransientKeyValue` / `DistributedWorkQueue` /
  `ServiceRegistry`, `Client.leadershipAsFlow(path)` (observer of who holds
  leadership, with a `LeadershipEvent` sealed type), and
  `EtcdLock.lockLostAsFlow()` / `DistributedSemaphore.permitLostAsFlow()`.
  Collecting a flow registers the underlying listener and cancelling the
  collector unregisters it; collection never starts or closes the recipe.
  Loss/lease flows fed from jetcd's lease-callback thread are unconditionally
  unlimited-buffered so that thread can never block.
- Listener-removal members completing existing add pairs:
  `PathChildrenCache.removeListener` / `removeRecoveryListener`,
  `ServiceCache.removeListenerForChanges` / `removeRecoveryListener`, and
  `removeLeaseListener` on `TransientKeyValue`, `DistributedWorkQueue`, and
  `ServiceRegistry`.

### Added (coroutines: suspending recipes)

- Suspending twins for every recipe's blocking entry points, in
  `io.etcd.recipes.coroutines`: queues (`receive`, `awaitEnqueue`, work-queue
  `awaitReceive`/`awaitAck`), barriers (`await`), locks (scoped `withLock` for
  the thread-owned mutex/RW-lock; `awaitAcquire`/`withPermit` for the semaphore),
  `DistributedAtomicLong` arithmetic, and the `start`/`waitOn…` lifecycle waits
  for cache, election, keyvalue, and discovery. Each runs the blocking call on
  `Dispatchers.IO` via `runInterruptible`, so coroutine cancellation aborts the
  wait and the recipe's existing cleanup (lease revoke / entry delete) runs.

### Changed

- `DistributedBarrierWithCount.waitOnBarrier`, `LeaderSelector`'s
  `waitOnLeadershipComplete`/`waitUntilFinished`, and
  `PathChildrenCache.waitOnStartComplete` now honor thread interruption (they
  parked on an uninterruptible monitor despite declaring
  `@Throws(InterruptedException)`). This makes the blocking API interruptible as
  documented and lets the coroutine twins cancel these waits cleanly; an
  interrupted barrier waiter stops counting toward the barrier before propagating.

### Added (coroutines: suspend KV/lease/txn + Flow watches)

- New `io.etcd.recipes.coroutines` package: a Kotlin-first async surface alongside
  the unchanged blocking API. Suspending twins of the `common` extensions
  (`awaitPutValue`, `awaitGetValue`, `awaitGetChildren`, `awaitTransaction`,
  `awaitLeaseGrant`, `awaitLock`, ...) share the blocking engine's retry policies
  and operation timeouts, backing off with `delay` instead of `Thread.sleep`;
  coroutine cancellation cancels the in-flight RPC future and is never retried.
- `Client.watchAsFlow(...)`: etcd watches as `Flow<WatchFlowEvent>`, backed by the
  existing resilient watcher — recovery transitions (`Suspended` / `Resubscribed` /
  `Resynced` / `Failed`) arrive in-band, compaction resync works through
  `resyncWith`, cancelling the collector closes the watcher, and the default
  unlimited buffer keeps a slow collector from ever stalling the watch dispatcher.
  `Client.watchEventsAsFlow(...)` flattens to `Flow<WatchEvent>`.
- `kotlinx-coroutines-core` is now an `api` dependency (Flow and suspend appear in
  public signatures).

### Added (locks: semaphore)

- `DistributedSemaphore` — distributed counting semaphore: the canonical permit
  count is CAS-created at the semaphore path and validated by every instance
  (mismatch throws `SemaphorePermitMismatchException` naming both values);
  lease-bound holder entries are admitted by create-revision rank, so capacity
  is provably never exceeded and grants are FIFO. Java-`Semaphore`-style
  instance-level holds: any thread may `release()` (LIFO among the instance's
  holds), acquisitions are never reentrant, and `withPermit { }` scopes a
  permit. Cooperative permit-lost semantics (`PermitLostListener`,
  `ConnectionState.LOST`, `release()` returns false, opt-in
  `interruptOnPermitLoss`) and leak-free `tryAcquire(timeout)` match the rest
  of the lock suite.
- `EtcdRecipeRuntimeException` is now `open`, so library exception subtypes
  (like `SemaphorePermitMismatchException`) stay catchable as the base type.

### Added (locks: read-write lock)

- `DistributedReadWriteLock` — fair (FIFO by create revision) shared/exclusive
  lock: readers share, writers exclude, queued writers cannot be starved by
  later readers. Lease-bound `read-`/`write-` entries with herd-free
  wait-on-nearest-conflicting-predecessor; write→read downgrade supported,
  read→write upgrade throws. Same thread-owned holds, reentrancy, leak-free
  `tryLock(timeout)`, and cooperative lock-lost semantics as `DistributedMutex`
  (both sides expose the shared `EtcdLock` surface).

### Added (locks: mutex)

- `DistributedMutex` — reentrant distributed lock on etcd's native lock service
  (server-side FIFO queuing, requireLeader applied by jetcd). Thread-per-acquisition
  holds (Curator parity), `tryLock(timeout)` whose timed-out attempts leak nothing
  (the per-acquisition lease revoke authoritatively aborts the server-side wait),
  `withLock { }`, and cooperative lock-lost handling: listener + state flip +
  `LOST` connection state, with interruption opt-in (`interruptOnLockLoss`). The
  raw `Client.lock`/`unlock` extensions gained rpc-resilience parameters
  (`lock` defaults to an unbounded wait by design).

### Added (queues: delayed delivery)

- `DistributedWorkQueue.enqueue(value, delay)` — the item stays invisible under
  `delayed/` until it matures, then consumers promote it into the queue (CAS, one
  winner) and it flows through the normal claim/ack lifecycle in ready-time
  order. Empty-queue waits wake when the earliest delayed item matures, so
  delivery is prompt without polling. Maturity is judged against client clocks
  (documented; skew shifts delivery by the skew).

### Added (queues: at-least-once work queue)

- `DistributedWorkQueue` — claim-based at-least-once delivery: `receive()` claims
  the head atomically (payload copied to `claimed/`, a lease-bound claim marker,
  and an attempt counter, all in one CAS transaction) and returns a `WorkItem`
  with `ack()` / `requeue()`. A crashed or partitioned consumer's markers expire
  with its lease (`visibilityTimeoutSecs`); every consumer's reclaim sweep
  returns orphaned items to their original FIFO position or dead-letters them
  after `maxDeliveries` (`deadLetters()` / `requeueDeadLetter` / `purgeDeadLetter`).
  Existing queues keep their at-most-once semantics untouched.

### Added (queues: bounded and non-blocking consumption)

- `tryDequeue(): ByteSequence?` (non-blocking) and `poll(timeout): ByteSequence?`
  (bounded, Duration and Long/TimeUnit overloads) on `DistributedQueue` and
  `DistributedPriorityQueue` — removes the "the only take blocks forever" footgun.
  `dequeue()` is unchanged: it is now the unbounded case of the same internal loop.
- `DistributedQueue.enqueueAll(values)` — atomic batch enqueue in one transaction;
  entries share the transaction's revision and keys embed the argument index so
  within-batch order follows argument order.

### Added (part 3: RPC timeouts/retries, client defaults)

- `RpcResilience` (`ResilienceConfig.rpc`): every blocking extension call
  (`putValue`, `getValue`, `deleteKey`, `leaseGrant`, ...) is bounded by a 30s
  per-attempt operation timeout and retries retriable statuses (UNAVAILABLE /
  INTERNAL / DEADLINE_EXCEEDED / timeout) under a bounded policy (4 × 250ms).
  Extension functions take an optional trailing `rpc` parameter; recipes pass
  their `resilience.rpc`.
- `ClientBuilder.withRecipeDefaults()` — `connectTimeout(5s)` +
  `retryMaxDuration(30s)`; `connectToEtcd` applies it before the caller's builder
  block, so user settings win.

### Changed (part 3)

- **Behavior:** blocking RPCs no longer park forever against an unreachable
  cluster — they fail after the operation timeout (`RpcResilience.DISABLED`
  restores unbounded one-shot semantics). `transaction { }` gets the timeout but
  is never retried: failed commits are ambiguous and CAS retries belong to the
  recipes' own loops.

### Added (part 2: self-healing leases, connection state)

- `Client.selfHealingKeepAlive(ttl, resilience, listener, establish)` — a keep-alive
  that re-grants an expired lease and re-runs the caller's establish hook, paced by
  `LeaseResilience`/`RetryPolicy`. Lease lifecycle is observable via `LeaseListener`
  events (`Suspended` / `Expired` / `Restored` / `Failed`); `addLeaseListener` on
  `TransientKeyValue` and `ServiceRegistry`.
- Connection-state machinery on `EtcdConnector`: `connectionState`
  (`CONNECTED` / `SUSPENDED` / `RECONNECTED` / `LOST`) derived passively from each
  recipe's own watch and lease streams, with `addConnectionStateListener`.
- `interruptOnLeaseLoss` constructor parameter on `LeaderSelector` (default true).

### Changed (part 2)

- **Behavior:** lease-holding recipes no longer lose their keys permanently after a
  partition longer than the TTL. `TransientKeyValue` re-puts its key,
  `ServiceRegistry` re-registers instances, `DistributedBarrier` re-arms, and a
  parked `DistributedBarrierWithCount` waiter's registration heals.
- **Behavior:** `LeaderSelector` steps down on leadership-lease loss instead of
  reporting `isLeader=true` while another node takes over (split-brain fix):
  `isLeader` turns false immediately, `waitUntilFinished()` releases, the
  `takeLeadership` thread is interrupted if still parked in user code, and
  `relinquishLeadership` always runs once leadership was taken (previously skipped
  when `takeLeadership` threw). Leadership is never auto-reclaimed.
- `ServiceInstance` JSON now encodes default field values (`encodeDefaults`), fixing
  a round-trip corruption where `registrationTimeUTC` was omitted whenever
  serialization ran in the same millisecond as construction — a later parse then
  back-filled a different timestamp. Old-format JSON still parses.

### Added (part 1: resilient watchers)

- `RetryPolicy` (exponential backoff / bounded / forever / never) pacing all watch
  recovery, and `WatchResilience` / `ResilienceConfig` to tune or disable it
  (`ResilienceConfig.DISABLED` restores pre-0.12 behavior). Every recipe constructor
  takes an optional trailing `resilience` parameter.
- Resilient watchers: `Client.watcher` now subscribes with jetcd's listener API,
  tracks the last observed revision, auto-resubscribes after fatal stream deaths
  (halt errors, "no leader"), and re-anchors after compaction via a resync hook.
  Recovery is observable through `WatchRecoveryListener` events
  (`Suspended` / `Resubscribed` / `Resynced` / `Failed`); `addRecoveryListener` on
  `PathChildrenCache` and `ServiceCache`.
- `Client.compact(revision, option)` KV extension.
- Fault-injection test harness (container pause/unpause/restart with a
  restart-stable client port) and fault tests under `io.etcd.recipes.fault`,
  gated behind `-PuseTestcontainers`.

### Changed (part 1)

- **Behavior:** watch-backed recipes no longer go silently stale after a fatal
  watch death. `PathChildrenCache` and `ServiceCache` reconcile their maps during a
  compaction resync; `LeaderSelector` re-probes the leader key after recovery (a
  node can no longer permanently drop out of re-election); barrier waiters and
  queue `dequeue()` re-check their condition after recovery and throw
  `EtcdRecipeRuntimeException` if recovery is abandoned instead of parking forever.
- Watchers request etcd progress notifications by default (`WatchResilience`),
  so watch blocks may observe `WatchResponse`s with an empty event list.
- `EtcdRecipeRuntimeException` gained an optional `cause` constructor parameter.

## [0.11.0] - 2026-06-03

Hardening release. Most of the work closes lease leaks, `close()`/wait deadlocks,
and keep-alive races found during a recipe-wide code review, plus cache key-handling
and priority-queue input fixes. Adds a few small APIs, broadens test coverage, and
bumps dependencies. No breaking API changes.

### Added

- `LeaderSelector.waitUntilFinished(...)` — a monitor-free blocking stop signal that
  is safe to call from inside `takeLeadership`. (#39)
- Optional `onKeepAliveError` callback threaded through `keepAlive`, `keepAliveWith`,
  and `putValuesWithKeepAlive` (default no-op, backward compatible). Every lease-holding
  recipe (`TransientKeyValue`, both barriers, `ServiceRegistry`, `LeaderSelector`) now
  records keep-alive stream death on its `exceptions` list, so a dropped renewal is
  observable instead of the key silently expiring while the recipe looks healthy. (#38)
- No-op default `LeaderListener.onError(Throwable)`; `reportLeader` routes caught
  callback failures to it. (#43)
- MockK into the version catalog and testing bundle, plus broad new unit/integration
  tests raising line coverage 82% → 88% and method coverage 74% → 84%. (#35, #36)

### Changed

- `DistributedPriorityQueue` Int-priority `enqueue` overloads now `require` the priority
  to be in `0..65535` instead of silently wrapping mod 65536 via `toUShort()` (which
  filed entries in the wrong sort bucket). (#44)
- `ServiceProvider.getInstance()` throws a typed, service-named `EtcdRecipeException` when
  no instances are registered, instead of a bare `NoSuchElementException` — consistent
  with `ServiceDiscovery.queryForInstance`. (#42)
- `EtcdConnector.exceptions` returns a defensive snapshot taken under the list monitor
  rather than the live `synchronizedList`, so a caller iterating while a worker thread
  appends can't hit a `ConcurrentModificationException`. (#42)
- `PathChildrenCache.rebuild()` reconciles the live map in place (`retainAll` + `putAll`)
  under `@Synchronized` instead of clear-then-refill, so `currentData`/`currentDataAsMap`
  never observe an empty/partial window. (#41)
- Leases are now revoked on normal cleanup (service, participation, and leadership leases)
  rather than lingering until TTL. (#39)
- `DistributedPriorityQueue.enqueue` retries on a lost optimistic CAS (bounded by
  `MAX_ENQUEUE_ATTEMPTS`) instead of surfacing "Failed to set key" to the caller. (#38)
- `deleteChildren` does a single atomic ranged prefix delete (`isPrefix` + `withPrevKV`)
  instead of a GET plus N per-key deletes. (#43)
- Dropped redundant post-CAS GET re-reads in `DistributedBarrier`,
  `DistributedBarrierWithCount`, `LeaderSelector`, and `DistributedAtomicLong`; these
  paths now gate solely on the authoritative `txn.isSucceeded`. (#44)
- A batch of low-risk cleanups across `common/`, `election/`, `cache/`, `discovery/`, and
  `util/` (findings #13–#25): bounded `getResponse` retry constant, `DispatchingWatcher`
  interrupt handling, `getCurrentData` param rename + KDoc, `toString()` additions, dead
  logger/import removal, and assorted read-coalescing. (#43)
- Makefile: `:=`/`sed` version extraction, `versioncheck` renamed to `versions`, default
  target is now `help`, and the `gradle` version-catalog key renamed to `gradle-wrapper`.
  `etcd-recipes-test-runners` is wired to the shadow plugin via the catalog. (#33, #35)
- Dependency bumps: Kotlin `2.4.0-RC2` → `2.4.0`, common-utils `2.8.2` → `2.9.0`,
  logback `1.5.33` → `1.5.34`, mockk `1.14.9` → `1.14.11`. (#33)

### Fixed

- `DistributedPriorityQueue`: restore strict priority/FIFO ordering on the empty-queue
  dequeue wait. After waking, re-query `getFirstChild` and prefer its head rather than
  returning whichever PUT the watcher happened to observe first, so the highest-priority
  (KEY) / oldest (MOD) item is always returned. (#46)
- `DistributedBarrierWithCount`: fix a keep-alive client leak / double-close race by
  promoting `keepAliveLease` to an `AtomicReference` claimed via a single `exchange(null)`,
  making cleanup exactly-once across the waiter, watch dispatcher, and `close()` threads. (#45)
- `LeaderSelector`: fix a `close()`/`takeLeadership` deadlock — the instance-wide
  `@Synchronized` is replaced with a narrow `electionLock` guarding only the leadership-claim
  CAS, so `close()` is uncontended while leadership is held. (#39)
- `PathChildrenCache`: fix child-name key-stripping for a trailing-slash `cachePath` (it
  over-stripped the first char of every child); all three sites now strip relative to one
  canonical `trailingPath`. (#40)
- Lease leaks on a failed CAS in `DistributedBarrierWithCount.waitOnBarrier`,
  `LeaderSelector.advertiseParticipation`, and `registerService`; the lease is revoked
  before returning/throwing. (#35, #38)
- CI: pass `disable_search: true` to the codecov action so empty aggregate/module reports
  can no longer reset the branch coverage badge to 0%. (#37)
- Makefile: default `DOCKER_HOST` to the per-user routing socket
  (`~/.docker/run/docker.sock`) so `make tests-container` no longer hangs on a dead
  Docker Desktop raw socket. (#37)

## [0.10.1] - 2026-05-15

Maintenance release: build/static-analysis tidy-up and documentation fixes. No
API or behavior changes.

### Changed

- detekt is now driven by `config/detekt/detekt.yml` layered on the bundled
  defaults (`buildUponDefaultConfig`); `MagicNumber` and `TooManyFunctions` are
  disabled there. Wildcard imports expanded to explicit imports, targeted
  `@Suppress` annotations added, and both `detekt-baseline.xml` files removed. (#31)
- Upgraded the Gradle wrapper 9.5.0 → 9.5.1. (#31)
- Dropped the library's own `String.ensureSuffix` extension in favor of
  `com.pambrose.common.util.ensureSuffix`. (#31)

### Fixed

- Maven Central coordinates in the docs were `com.pambrose.etcd-recipes:etcd-recipes`;
  the published artifact is `com.pambrose:etcd-recipes`. Corrected the README badge
  and dependency snippets, `llms.txt`, and `RELEASE_NOTES.md`. (#32)

### Removed

- Dead codebeat and SonarCloud badges from the README. (#31)

## [0.10.0] - 2026-05-13

### Added

- Multi-container test variant. Each distributed-recipe participant now runs in its own
  container against a shared etcd container, complementing the existing thread-based
  tests. Lives in `etcd-recipes-test-runners/` (a runnable shadow JAR dispatched by
  `--recipe`/`--role`) plus `ContainerBarrierTest`, `ContainerLeaderSelectorTest`,
  `ContainerQueueTest`, `ContainerCounterTest`, and `ContainerServiceDiscoveryTest`.
  Gated by `-PuseTestcontainers`. (#28, #29)
- `make tests-container` target for running just the multi-container tests.
- Testcontainers test mode: `-PuseTestcontainers` (or `make tests-tc`) runs every test
  against an ephemeral etcd container instead of `localhost:2379`. (#24)
- CI workflow (`.github/workflows/ci.yml`) running `check` under Testcontainers on
  pushes and PRs to `master`. (#24)
- Dokka-generated API docs, Kover coverage reports, Codecov upload, and Detekt v2
  static analysis. (#25, #26)
- Kotest test framework alongside the existing JUnit 5 setup; new tests use
  `StringSpec` with an `init {}` block. (#20)

### Changed

- Replaced Jacoco with [Kover](https://github.com/Kotlin/kotlinx-kover) for coverage. (#25)
- Replaced Java DSL build with Gradle 9 + Kotlin DSL; dependency versions moved to a
  Gradle version catalog (`gradle/libs.versions.toml`). (#20)
- Test suite is ~7× faster after switching fixed `sleep(...)` settle calls to poll-based
  waits, plus parallel forking via `maxParallelForks`. Each test class runs in its own
  forked JVM (`forkEvery=1`) so background threads can't leak across specs.
- Migrated atomic primitives to the stdlib `kotlin.concurrent.atomics` package; dropped
  the `kotlinx-atomicfu` plugin and its compile-time dependency.
- `Makefile` rewritten with lazy version guards, a `help` target, and `lintKotlin` as
  the lint entry point. (#26, #27)

### Fixed

- Lease leaks, close-ordering, and counter races across recipes. (#23)
- `ServiceCache.close()` deadlock when no watch events arrived; `LeaderSelector` could
  not be reused after `close()`; `DistributedBarrierWithCount.waitOnBarrier` missed peer
  joins because it watched a per-client unique path instead of the shared waiting
  prefix. (#22)
- Vert.x event-loop deadlocks in `LeaderSelector` and `AbstractQueue` caused by
  callbacks holding locks across gRPC responses. (#21)

## [0.9.20] - 2021-08-08

- Earlier history not transcribed; see git tags for individual releases between
  0.1.0 (2019-10-14) and 0.9.20.

## [0.1.0] - 2019-10-14

### Added

- Initial commit.
