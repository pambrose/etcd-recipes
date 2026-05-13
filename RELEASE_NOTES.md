# Release Notes

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
implementation("com.pambrose.etcd-recipes:etcd-recipes:0.10.0")
```

See `CHANGELOG.md` for the full list of changes since 0.9.20.
