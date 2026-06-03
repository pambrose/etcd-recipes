# Release Notes

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
