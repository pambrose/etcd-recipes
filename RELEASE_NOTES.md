# Release Notes

## 0.12.0 — 2026-07-26

The largest release since the project began. Recipes now survive the failures that
previously left them silently broken, a whole `lock/` package and an at-least-once work
queue join the recipe set, a coroutine API sits alongside the blocking one, values can be
typed through a codec instead of hand-marshalled, and there is a metrics SPI plus four
optional integration modules. The published core artifact is renamed — see *Upgrading*.

### Highlights

**Connection resilience, on by default.** Watchers created through this library used to
be blind to fatal stream deaths: a watch killed by compaction, a halt error, or "no
leader" silently stopped delivering events, so caches went permanently stale and parked
waiters hung forever. jetcd still handles transient blips; this release recovers the
deaths jetcd abandons. Watchers track the last observed revision, auto-resubscribe under
a configurable `RetryPolicy`, and re-anchor after compaction via a resync hook that lets
`PathChildrenCache` and `ServiceCache` reconcile their maps. Parked waiters re-probe
their condition after every recovery and fail loudly if recovery is abandoned rather
than hanging. Recovery is observable through `WatchRecoveryListener`.

**Leases self-heal, and leaders step down.** A partition longer than the lease TTL used
to cost a recipe its keys permanently — etcd had deleted them and nothing re-created
them. `selfHealingKeepAlive` re-grants an expired lease and re-runs the recipe's
establish hook, so `TransientKeyValue` re-puts its key, `ServiceRegistry` re-registers,
and `DistributedBarrier` re-arms. Leadership is the deliberate exception: on
leadership-lease loss `LeaderSelector` **steps down** — `isLeader` turns false
immediately, the `takeLeadership` block is released, and `relinquishLeadership` runs —
instead of reporting `isLeader=true` while another node has already taken over. That was
a split-brain window; leadership is never auto-reclaimed.

**Blocking RPCs are bounded.** Every blocking extension call used to sit on an unbounded
`future.get()`; against an unreachable cluster it parked forever. Each attempt now has a
30 s operation timeout and retriable statuses are retried under a bounded policy.
`transaction { }` gets the timeout but is never retried — a failed commit is ambiguous,
and CAS retry decisions belong to the recipes' own loops.

**A lost-wakeup race in every waiter.** Waiters across the locks, barriers, and queues
subscribed their DELETE/PUT watch without a start revision, so the watch began at
whatever revision etcd assigned when it processed the create. An event landing in that
establishment window was missed by both the watch and the pre-live recheck, and an
unbounded wait then parked forever. Every waiter now anchors its watch at the revision
its own pre-subscribe read observed, making the pre-live recheck a fast path rather than
the only guard.

**Distributed locks.** A new `io.etcd.recipes.lock` package:

- `DistributedMutex` — reentrant lock on etcd's native lock service (server-side FIFO
  queuing). Thread-per-acquisition holds for Curator parity, a `tryLock(timeout)` whose
  timed-out attempts leak nothing, and `withLock { }`.
- `DistributedReadWriteLock` — fair shared/exclusive lock, FIFO by arrival revision, so
  a queued writer is never starved by later readers. Write→read downgrade supported;
  read→write upgrade throws.
- `DistributedSemaphore` — counting semaphore whose permit count is stored once and
  validated by every instance, with FIFO grants and instance-level, Java-`Semaphore`-style
  holds.

All three handle loss **cooperatively**: if a holder is partitioned past its TTL, etcd
grants ownership onward and the dispossessed holder observes it — a listener fires,
connection state reports `LOST`, and `unlock()` / `release()` returns false. Interruption
is opt-in, and ownership is never auto-reclaimed.

**Reliable and bounded queues.** `DistributedWorkQueue` is the at-least-once counterpart
to the existing at-most-once queues: `receive()` claims the head atomically and returns a
`WorkItem` with `ack()` / `requeue()`. A crashed consumer's claim markers expire with its
lease and any consumer's reclaim sweep returns the item to its original FIFO position —
or dead-letters it after `maxDeliveries`. `enqueue(value, delay)` defers delivery without
blocking immediate items. The plain queues gained `tryDequeue()`, `poll(timeout)`, and
atomic `enqueueAll(values)`, removing the "the only take blocks forever" footgun.

**Election and discovery.** `LeaderLatch` acquires leadership and **holds it until
`close()`** — the shape `LeaderSelector`'s callback scoping cannot express — and
interoperates with selectors in the same election. `LeaderObserver` watches an election
without being a candidate. `ServiceProvider` became a real client-side load balancer:
a pluggable `ProviderStrategy` (random / round-robin / sticky), optional watch-backed
in-memory reads via `start()`, and `noteError(instance)` to eject a failing instance for
a down window before it automatically becomes eligible again.

**A coroutine API.** `io.etcd.recipes.coroutines` adds suspending twins of the `common`
extensions (`awaitPutValue`, `awaitGetValue`, `awaitTransaction`, …) sharing the blocking
engine's retry policies and timeouts, but backing off with `delay` and treating
cancellation as authoritative. Every recipe's blocking waits get suspending twins that
run on `Dispatchers.IO` via `runInterruptible`, so cancelling the coroutine aborts the
wait and the recipe's normal cleanup runs. Watches and every recipe listener stream
become `Flow`s. Making this work required fixing blocking waits that parked on an
uninterruptible monitor despite declaring `@Throws(InterruptedException)` — those are now
genuinely interruptible.

**Typed values.** `EtcdCodec<T>` is a small `encode`/`decode` SPI with built-in
`ByteSequenceCodec`, `StringCodec`, and `jsonCodec<T>()` implementations. It types the KV
extensions (`putValue`/`getValue`) and every recipe that carried a raw payload:
`NodeCache<T>` (a new watch-backed single-key cache, the counterpart to
`PathChildrenCache`), `TypedPathChildrenCache<T>`, `TypedDistributedQueue<T>` /
`TypedDistributedPriorityQueue<T>`, `TypedTransientKeyValue<T>`, and `ServiceInstance`'s
`payload<T>(codec)`. Each typed recipe is a composition wrapper exposing the underlying
recipe as `untyped`; no existing class changed.

**Observability.** `EtcdMetrics` is a dependency-free SPI with empty default method
bodies — implement only the seams you care about, and the default `NoOp` costs nothing.
It covers RPC latency/retries/outcome, watch recovery, keep-alive events, lock wait and
hold times, leadership transitions, queue latency, and cache syncs. Background failures
are no longer poll-only: a `BackgroundExceptionListener` fires the moment one occurs,
tagged with the recipe's identity, and `isHealthy()` / `ping()` report current rather
than accumulated state. Background-thread logs carry that same identity in the SLF4J MDC
under `etcd.recipe`, so a warning from a healer thread is no longer anonymous.

**Framework integration.** `connectToEtcd` now takes a declarative
`EtcdConnectionConfig` — auth, key `namespace`, TLS, timeouts — so the common options no
longer require the `initReceiver` escape hatch. Four optional modules build on it, each
published separately so the core stays dependency-free:

| Module | What it adds |
|---|---|
| `etcd-recipes-jackson` | `JacksonCodec<T>` for projects preferring Jackson |
| `etcd-recipes-micrometer` | `MicrometerEtcdMetrics` backend plus `EtcdGauges` binders |
| `etcd-recipes-spring-boot-starter` | Auto-configured `Client` / `EtcdRecipes` beans from `etcd.recipes.*`, optional Actuator health indicator |
| `etcd-recipes-ktor` | Ktor plugin exposing `application.etcdClient` / `etcdRecipes` |

**Documentation site.** A 31-page site at
<https://pambrose.github.io/etcd-recipes/>, with Kotlin/Java tabs on every recipe page
and a Java-interop guide. Its 306 code examples are not written into the Markdown — each
is a real source file in a Gradle test source set, embedded at build time, so the whole
site is type-checked against the actual API on every build and cannot silently rot.

**Build.** Kotlin 2.4.10, Gradle wrapper 9.6.1, Spring Boot 4.1.x, all versions
consolidated into the version catalog, the last `java.util.concurrent.atomic` usages
converted to the stdlib `kotlin.concurrent.atomics`, and `./etcd-start.sh` /
`./etcd-stop.sh` helpers with matching `make` targets.

### Upgrading

- **The core artifact is renamed** `com.pambrose:etcd-recipes` →
  `com.pambrose:etcd-recipes-core`, so it reads as a sibling of the new
  `etcd-recipes-micrometer` / `-jackson` / `-spring-boot-starter` / `-ktor` modules.
  Update the coordinate in your build file. **No package, class, or method name
  changed** — everything is still under `io.etcd.recipes.*`, so nothing else moves.
- **Behavior changes, all in the direction of failing loudly rather than hanging.**
  Blocking calls that used to park forever against an unreachable cluster now fail after
  the operation timeout; parked waiters unpark with an `EtcdRecipeRuntimeException` when
  watch recovery is abandoned; and `LeaderSelector` steps down on leadership-lease loss
  instead of continuing to report `isLeader=true`. Pass `ResilienceConfig.DISABLED` (and
  `RpcResilience.DISABLED`) to restore the pre-0.12 semantics.
- Watchers request etcd progress notifications by default, so raw watch blocks may now
  observe `WatchResponse`s with an empty event list. Guard accordingly if you iterate
  events without checking.
- `kotlinx-coroutines-core` is now an `api` dependency — `Flow` and `suspend` appear in
  public signatures.
- `ServiceInstance` JSON now encodes default field values, fixing a round-trip corruption
  where `registrationTimeUTC` was dropped when serialization ran in the same millisecond
  as construction. Old-format JSON still parses.

### Maven coordinates

```kotlin
implementation("com.pambrose:etcd-recipes-core:0.12.0")

// optional
implementation("com.pambrose:etcd-recipes-jackson:0.12.0")
implementation("com.pambrose:etcd-recipes-micrometer:0.12.0")
implementation("com.pambrose:etcd-recipes-spring-boot-starter:0.12.0")
implementation("com.pambrose:etcd-recipes-ktor:0.12.0")
```

## 0.11.0 — 2026-06-03

A hardening release. The bulk of the work is a recipe-wide code-review pass that
closes lease leaks, `close()`/wait deadlocks, and keep-alive races, with a few
small API additions, broader test coverage, and dependency bumps. No breaking
API changes.

### Highlights

**Lease leaks closed.** Several paths granted an etcd lease and then failed (a lost
CAS, an early `close()`) without revoking it, leaving the lease to linger until its
TTL expired. Failed-CAS paths in `DistributedBarrierWithCount`, `LeaderSelector`, and
`registerService` now revoke before returning, and normal cleanup revokes the service,
participation, and leadership leases instead of waiting for TTL.

**Deadlocks and races fixed.**

- `LeaderSelector.close()` could deadlock against an active `takeLeadership`: the
  instance-wide `@Synchronized` is now a narrow `electionLock` around only the
  leadership-claim CAS, and a new monitor-free `waitUntilFinished(...)` is safe to call
  from inside `takeLeadership`.
- `DistributedBarrierWithCount` could leak a keep-alive stream when `close()` raced the
  waiter; the keep-alive client is now an `AtomicReference` claimed via a single
  `exchange(null)`, so close/delete happens exactly once.
- `PathChildrenCache.rebuild()` reconciles its map in place under `@Synchronized` instead
  of clear-then-refill, so readers never see an empty/partial window.

**Keep-alive failures are now observable.** A dropped lease renewal used to let the key
silently expire while the recipe still looked healthy. An optional `onKeepAliveError`
callback (default no-op) is threaded through the keep-alive helpers, and every
lease-holding recipe records keep-alive stream death on its `exceptions` list.

**Input and key-handling correctness.**

- `DistributedPriorityQueue` Int-priority `enqueue` overloads now reject priorities
  outside `0..65535` instead of silently wrapping them into the wrong sort bucket, and
  the empty-queue dequeue wait re-queries the head so strict priority/FIFO ordering holds
  even under a producer/watcher race.
- `PathChildrenCache` strips child names consistently for a trailing-slash `cachePath`
  (it previously over-stripped the first character of every child).
- `ServiceProvider.getInstance()` throws a typed, service-named `EtcdRecipeException` when
  no instances are registered, and `EtcdConnector.exceptions` hands back a defensive
  snapshot rather than the live list.

**Tests, build, and dependencies.** New MockK and integration tests raise coverage to
88% line / 84% method; the codecov badge no longer resets to 0% on aborted runs; the
Makefile no longer hangs on a dead Docker socket; and Kotlin (`2.4.0`), common-utils,
logback, and mockk were bumped.

### Maven coordinates

```kotlin
implementation("com.pambrose:etcd-recipes:0.11.0")
```

## 0.10.1 — 2026-05-15

Maintenance release. No API or behavior changes — just build, static-analysis,
and documentation tidy-up.

### Highlights

**detekt configuration.** Static analysis is now driven by a checked-in
`config/detekt/detekt.yml` layered on detekt's bundled defaults. `MagicNumber` and
`TooManyFunctions` are disabled there rather than masked by per-module
`detekt-baseline.xml` files (both removed); wildcard imports were expanded to
explicit imports and a handful of targeted `@Suppress` annotations added.

**Gradle wrapper.** Upgraded 9.5.0 → 9.5.1.

**Internal cleanup.** Dropped the library's own `String.ensureSuffix` extension in
favor of `com.pambrose.common.util.ensureSuffix`.

**Documentation.** Fixed the Maven Central coordinates throughout the docs — the
published artifact is `com.pambrose:etcd-recipes`, not
`com.pambrose.etcd-recipes:etcd-recipes`. Removed the dead codebeat and SonarCloud
README badges.

### Maven coordinates

```kotlin
implementation("com.pambrose:etcd-recipes:0.10.1")
```

## 0.10.0 — 2026-05-13

The 0.10.0 release modernizes the build, hardens the recipes against several races
discovered under heavier test coverage, and adds a second test variant that drives
each distributed participant from its own container.

### Highlights

**Multi-container test variant.** The thread-based tests simulate distributed clients
with N threads in a single JVM. A new variant runs each participant as its own
container coordinating through a shared etcd container. Five tests cover the
coordination-heavy recipes (barrier, leader election, queue, counter, service
discovery). Live alongside the thread tests under `-PuseTestcontainers`, or invoked
directly with `make tests-container`.

**Testcontainers test mode.** `-PuseTestcontainers` (also `make tests-tc`) runs every
test against an ephemeral etcd container instead of `localhost:2379`. CI exercises
this mode on every push and PR.

**Build modernization.** Gradle 9 with the Kotlin DSL; dependencies tracked in
`gradle/libs.versions.toml`; Dokka for API docs; Kover (replacing Jacoco) for coverage
with Codecov upload; Detekt v2 for static analysis; Kotest alongside JUnit 5.

**Concurrency fixes.** Several long-standing races have been closed:

- Vert.x event-loop deadlocks in `LeaderSelector` and `AbstractQueue` where callbacks
  held a lock while a gRPC response was pending.
- `ServiceCache.close()` deadlock when no watch events ever arrived; the
  `startThreadComplete` signal now fires in `start()` rather than inside the watcher
  callback.
- `LeaderSelector` could not be reused after `close()` because the internal
  `ExecutorService` was shut down; `start()` now re-creates the executor if necessary.
- `DistributedBarrierWithCount.waitOnBarrier` watched a per-client unique path rather
  than the shared waiting prefix, so peer joins were missed and the barrier could
  hang.
- Lease leaks, close-ordering bugs, and counter races across recipes.

**Performance.** The test suite is ~7× faster after switching fixed `sleep(...)`
settle calls to poll-based waits and forking each test class into its own JVM with
`maxParallelForks`.

### Upgrading

- JDK 17 toolchain (was JDK 8 in earlier releases).
- `kotlinx-atomicfu` has been removed in favor of `kotlin.concurrent.atomics` from
  the stdlib. Recipes that previously imported `kotlinx.atomicfu.AtomicBoolean` should
  now import `kotlin.concurrent.atomics.AtomicBoolean` and use `.load()` / `.store()`.

### Maven coordinates

```kotlin
implementation("com.pambrose:etcd-recipes:0.10.0")
```

See `CHANGELOG.md` for the full list of changes since 0.9.20.
