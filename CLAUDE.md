# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`etcd-recipes` is a Kotlin/Java/JVM client library for [etcd](https://etcd.io) v3, built on top of [jetcd](https://github.com/etcd-io/jetcd). It aims to provide for etcd what Apache Curator provides for ZooKeeper: higher-level distributed coordination primitives (barriers, locks, leader election, service discovery, queues, distributed counters, caches).

## Build, Test, Lint

JDK 17 toolchain (configured in `build.gradle.kts` via `kotlin { jvmToolchain(17) }`). Gradle wrapper is pinned to 9.6.1 (the `gradle-wrapper` key in the version catalog). Common entry points are in the `Makefile` (`make help` lists everything):

- `make build` — `./gradlew clean build -x test` (build without running tests)
- `make tests` — full test suite against a local etcd at `localhost:2379`
- `make tests-tc` — full suite against an ephemeral Testcontainers etcd (no local etcd needed)
- `make tests-container` — multi-container tests only (each participant in its own container)
- `make all-tests` — all three variants in sequence
- `make etcd-start` / `make etcd-stop` — start / gracefully stop a local etcd
- `make lint` — `./gradlew lintKotlin detekt`
- `make coverage` — Kover HTML + XML reports + summary
- `make kdocs` — Dokka HTML / Javadoc
- `make site` — serve the documentation site locally
- `make docs-check` — compile the website code snippets, then build the site in strict mode (what CI runs)
- `make versions` — `./gradlew dependencyUpdates --no-parallel`
- `make refresh` — refresh dependencies
- `make upgrade-wrapper` — bumps the Gradle wrapper

Run a single test class:
```
./gradlew :etcd-recipes-core:test --tests "io.etcd.recipes.barrier.DistributedBarrierTests"
```
Tests use JUnit 5 (`useJUnitPlatform()`) plus Kotest and Kluent assertions. When adding new Kotlin tests, prefer Kotest with `StringSpec()` and an `init {}` block, plus MockK where appropriate. Coverage is Kover (the project moved off Jacoco in 0.10.0).

## Running etcd locally

The `make tests` target and the examples expect a local etcd at `http://localhost:2379`. Start one with the helper script (or `make etcd-start`):
```
./etcd-start.sh
```
which runs `etcd --listen-client-urls=http://localhost:2379 --advertise-client-urls=http://localhost:2379`. `./etcd-stop.sh` (or `make etcd-stop`) stops it gracefully — SIGTERM, then SIGKILL after a 10s grace period. The local data directory `default.etcd/` is gitignored.

`make tests-tc` and `make tests-container` use Testcontainers and require Docker — no local etcd is needed for those.

## Module Layout

Multi-module Gradle build (`settings.gradle.kts`):

- **`etcd-recipes-core/`** — the library itself, published as `com.pambrose:etcd-recipes-core`. Source is organized by recipe under `io.etcd.recipes.*`:
  - `barrier/` — `DistributedBarrier`, `DistributedBarrierWithCount`, `DistributedDoubleBarrier`
  - `cache/` — `PathChildrenCache` (prefix), `NodeCache<T>` (single key), `TypedPathChildrenCache<T>`
  - `counter/` — `DistributedAtomicLong`
  - `discovery/` — `ServiceDiscovery`, `ServiceCache`, `ServiceInstance`, `ServiceProvider` (+ `ProviderStrategy`)
  - `election/` — `LeaderSelector`, `LeaderLatch`, `LeaderObserver`, `LeaderSelectorListener`, `Participant`
  - `keyvalue/` — `TransientKeyValue`, `TypedTransientKeyValue<T>`
  - `lock/` — `DistributedMutex`, `DistributedReadWriteLock`, `DistributedSemaphore` (share the `EtcdLock` surface)
  - `queue/` — `DistributedQueue`, `DistributedPriorityQueue` (share `AbstractQueue`), `DistributedWorkQueue` (at-least-once), plus the `Typed*` wrappers
  - `coroutines/` — suspending twins of the blocking entry points and `Flow` surfaces (`watchAsFlow`, `eventsAsFlow`, …). Additive: the blocking API is unchanged and stays the Java-facing one.
  - `common/` — extension functions over jetcd's `Client`/`KV`/`Lease`/`Watch`/`Txn` and shared base infrastructure (see below)
  - `util/` — small CLI utilities (`ShowKeys`, etc.)
- **`etcd-recipes-examples/`** — runnable Java and Kotlin examples mirroring each recipe; useful as living documentation. Examples are typically `main()` programs that call into the library.
- **Satellite modules** — each published as its own Maven Central artifact, each depending on `project(":etcd-recipes-core")`. The core module itself stays dependency-free of all of them:
  - **`etcd-recipes-jackson/`** — `JacksonCodec<T>`, an `EtcdCodec` for projects preferring Jackson to kotlinx-serialization.
  - **`etcd-recipes-micrometer/`** — `MicrometerEtcdMetrics` (an `EtcdMetrics` backend) and `EtcdGauges` binders.
  - **`etcd-recipes-spring-boot-starter/`** — auto-configures `Client` / `EtcdRecipes` beans from `etcd.recipes.*` properties, plus an optional Actuator health indicator. Spring Boot 4.1.x.
  - **`etcd-recipes-ktor/`** — a Ktor `Application` plugin exposing `Application.etcdClient` / `etcdRecipes`. Ktor 3.5.x.
- **`etcd-recipes-test-runners/`** — test-only module. A runnable shadow JAR with a dispatcher `main()` that routes on `--recipe`/`--role` to per-recipe runners (barrier waiter, election participant, queue consumer, counter incrementer, service registration). Used by the container-based tests under `etcd-recipes-core/src/test/kotlin/io/etcd/recipes/container/`; each participant runs as its own Testcontainers container against a shared etcd container. Wired into the library's test task only when `-PuseTestcontainers` is set.
- **`website/`** — the [Zensical](https://zensical.org) documentation site published to <https://pambrose.github.io/etcd-recipes/>. No code example is written into the Markdown: each is a real source file under a `src/test/.../website/` source set in the module whose API it documents, embedded at build time via `pymdownx.snippets`, so `./gradlew compileTestKotlin compileTestJava` type-checks every example on the site. Snippet files are plain uninvoked functions — they contribute zero tests. `make docs-check` compiles them and builds the site strictly (a dangling snippet reference fails the build).

## Architecture notes

The recipes are layered on a thin Kotlin extension layer over jetcd, not a wrapper around it:

- **Extensions in `common/`** (`ClientExtensions`, `KVExtensions`, `LeaseExtensions`, `WatchExtensions`, `TxnExtensions`, `ByteSequenceExtensions`, etc.) are where most direct etcd interaction happens. New recipes should compose these rather than reach into jetcd directly.
- **`EtcdConnector`** (`common/EtcdConnector.kt`) is the shared base for stateful recipes. It owns the jetcd `Client`, tracks `startCalled` / `closeCalled` lifecycle (atomic flags), accumulates background-thread exceptions in `exceptionList`, and implements `Closeable`. Recipes generally follow a `start()` … `close()` lifecycle and surface async failures via `exceptions` rather than throwing from a worker thread.
- **`EtcdRecipeException` / `EtcdRecipeRuntimeException`** are the library's own exception types — prefer these over leaking jetcd exception types. `EtcdRecipeRuntimeException` is `open` and takes an optional `cause`, so library subtypes (e.g. `SemaphorePermitMismatchException`) stay catchable as the base type.
- **Resilience is on by default and threaded, not global.** Every recipe constructor takes an optional trailing `resilience: ResilienceConfig` bundling `WatchResilience` (fatal-watch-death resubscribe + compaction resync, paced by a `RetryPolicy`), `LeaseResilience` (`selfHealingKeepAlive` re-grants an expired lease and re-runs the establish hook), and `RpcResilience` (a per-attempt operation timeout plus bounded retries on retriable gRPC statuses). Extension functions take an optional trailing `rpc` parameter; recipes pass their own `resilience.rpc` down. `ResilienceConfig.DISABLED` restores pre-0.12 behavior. New blocking extensions should accept and honor `rpc` rather than calling `future.get()` unbounded, and `transaction { }` is deliberately never retried (an ambiguous commit belongs to the recipe's own CAS loop).
- **Failures surface through one sink.** `EtcdConnector.recordException` feeds both the pull-only `exceptions` snapshot and the push `BackgroundExceptionListener` (with a `"Recipe[path]"` context string); `connectionState` (`CONNECTED`/`SUSPENDED`/`RECONNECTED`/`LOST`) and `isHealthy()` / `ping()` report current rather than accumulated state. A new background failure path should route through `recordException`, not throw from a worker thread and not log-and-drop.
- **`EtcdMetrics`** (`common/EtcdMetrics.kt`) is a dependency-free instrumentation SPI with empty default method bodies — adding a seam never breaks an implementation, and the default `NoOp` costs nothing. Seams run on hot threads (RPC callers, the watch dispatcher, the lease healer), so they must not block. Keys, paths, and lease ids are passed as *context*, never as metric tags; the Micrometer binding drops them on purpose to keep cardinality bounded.
- **Typed values go through `EtcdCodec<T>`** (`encode`/`decode`), not hand-marshalled `ByteSequence`. Built-ins: `ByteSequenceCodec`, `StringCodec`, `jsonCodec<T>()`; `JacksonCodec<T>` lives in the satellite module. The `Typed*` recipes are composition wrappers exposing the underlying recipe as `untyped` — they add no behavior, so a fix belongs in the untyped recipe.
- **Background-thread logging** is wrapped in `EtcdConnector.withRecipeLoggingContext { }`, which puts the recipe identity in the SLF4J MDC under `EtcdConnector.RECIPE_MDC_KEY` (`"etcd.recipe"`) and restores any prior value. New async runnables should be wrapped the same way, otherwise their logs are anonymous — every recipe's healer threads share a name.
- **Concurrency**: recipes rely on `kotlinx-coroutines`, `kotlin.concurrent.atomics` (stdlib `AtomicBoolean` / `AtomicReference`), and helpers from `com.pambrose.common-utils` (`BooleanMonitor`). Several Kotlin opt-ins are enabled compiler-wide: `kotlin.time.ExperimentalTime`, `kotlin.ExperimentalUnsignedTypes`, `kotlin.concurrent.atomics.ExperimentalAtomicApi` — assume these are available without per-file `@OptIn`.
- **Watcher callbacks** run on jetcd's Vert.x event loop. Anything that needs another gRPC response (or contends with a lock the caller holds while waiting on gRPC) will deadlock the event loop. The `Client.watcher` extension hops callbacks onto a dedicated single-thread executor; recipes should still avoid blocking the callback on locks held by callers waiting on gRPC.

## Tests

- **Thread-based** (`*Tests.kt` under each recipe directory) — N threads in a single JVM simulate distributed clients via `blockingThreads(...)` / `nonblockingThreads(...)` in `etcd-recipes-core/src/test/kotlin/io/etcd/recipes/common/TestExtensions.kt`.
- **Container-based** (`etcd-recipes-core/src/test/kotlin/io/etcd/recipes/container/Container*Test.kt`) — each participant runs as its own container coordinating through a shared etcd container. Uses `EtcdContainerNetwork`, `Participant.newContainer(...)`, and `Client.awaitResults(...)` from the `common/` test fixtures. The runners write their outcome to `/test-results/{testId}/{participantId}` as JSON; orchestrators decode the per-recipe payload type via the `ParticipantResult.payload<T>()` extension. Gated by `assumeTrue(testcontainers=true)` so default `./gradlew check` skips them cleanly.

Test JVMs fork per class (`forkEvery=1`) so background threads / etcd watch connections from one spec don't interfere with the next. `maxParallelForks = cores/2` lets multiple test classes run concurrently against the same etcd; each test namespaces its keys by class name to avoid collisions.

## Versioning

The current development branch is named after the version (e.g. `0.12.0`). Library version lives in `gradle.properties` (`version=...`); Kotlin/dependency versions live in `gradle/libs.versions.toml`; bump both when releasing. All modules share the one version.

The published core coordinate changed in 0.12.0: `com.pambrose:etcd-recipes` → **`com.pambrose:etcd-recipes-core`** (the artifactId derives from the Gradle module name). The satellite modules publish under their own module names. The bare `etcd-recipes` prefix still names the repo, the Dokka footer, and the sibling modules — only the core module carries the `-core` suffix.

The version string is duplicated in the docs (README download snippets, `website/etcd-recipes/docs/**`); a release bumps those alongside `gradle.properties`. `git grep <old-version>` finds them all.
