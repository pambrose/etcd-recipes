# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`etcd-recipes` is a Kotlin/Java/JVM client library for [etcd](https://etcd.io) v3, built on top of [jetcd](https://github.com/etcd-io/jetcd). It aims to provide for etcd what Apache Curator provides for ZooKeeper: higher-level distributed coordination primitives (barriers, locks, leader election, service discovery, queues, distributed counters, caches).

## Build, Test, Lint

JDK 17 toolchain (configured in `build.gradle.kts` via `kotlin { jvmToolchain(17) }`). Gradle wrapper is pinned to 9.5.0. Common entry points are in the `Makefile` (`make help` lists everything):

- `make build` — `./gradlew clean build -xtest` (build without running tests)
- `make tests` — full test suite against a local etcd at `localhost:2379`
- `make tests-tc` — full suite against an ephemeral Testcontainers etcd (no local etcd needed)
- `make tests-container` — multi-container tests only (each participant in its own container)
- `make lint` — `./gradlew lintKotlin detekt`
- `make coverage` — Kover HTML + XML reports + summary
- `make kdocs` — Dokka HTML / Javadoc
- `make versioncheck` — `./gradlew dependencyUpdates --no-parallel`
- `make refresh` — refresh dependencies
- `make upgrade-wrapper` — bumps the Gradle wrapper

Run a single test class:
```
./gradlew :etcd-recipes:test --tests "io.etcd.recipes.barrier.DistributedBarrierTests"
```
Tests use JUnit 5 (`useJUnitPlatform()`) plus Kotest and Kluent assertions. When adding new Kotlin tests, prefer Kotest with `StringSpec()` and an `init {}` block, plus MockK where appropriate. Coverage is Kover (the project moved off Jacoco in 0.10.0).

## Running etcd locally

The `make tests` target and the examples expect a local etcd at `http://localhost:2379`. Start one with the helper script:
```
./etcd.sh
```
which runs `etcd --listen-client-urls=http://localhost:2379 --advertise-client-urls=http://localhost:2379`. The local data directory `default.etcd/` is gitignored.

`make tests-tc` and `make tests-container` use Testcontainers and require Docker — no local etcd is needed for those.

## Module Layout

Multi-module Gradle build (`settings.gradle.kts`):

- **`etcd-recipes/`** — the library itself. Source is organized by recipe under `io.etcd.recipes.*`:
  - `barrier/` — `DistributedBarrier`, `DistributedBarrierWithCount`, `DistributedDoubleBarrier`
  - `cache/` — `PathChildrenCache`
  - `counter/` — `DistributedAtomicLong`
  - `discovery/` — `ServiceDiscovery`, `ServiceCache`, `ServiceInstance`, `ServiceProvider`
  - `election/` — `LeaderSelector`, `LeaderSelectorListener`, `Participant`
  - `keyvalue/` — `TransientKeyValue`
  - `queue/` — `DistributedQueue`, `DistributedPriorityQueue` (share `AbstractQueue`)
  - `common/` — extension functions over jetcd's `Client`/`KV`/`Lease`/`Watch`/`Txn` and shared base infrastructure (see below)
  - `util/` — small CLI utilities (`ShowKeys`, etc.)
- **`etcd-recipes-examples/`** — runnable Java and Kotlin examples mirroring each recipe; useful as living documentation. Examples are typically `main()` programs that call into the library.
- **`etcd-recipes-test-runners/`** — test-only module. A runnable shadow JAR with a dispatcher `main()` that routes on `--recipe`/`--role` to per-recipe runners (barrier waiter, election participant, queue consumer, counter incrementer, service registration). Used by the container-based tests under `etcd-recipes/src/test/kotlin/io/etcd/recipes/container/`; each participant runs as its own Testcontainers container against a shared etcd container. Wired into the library's test task only when `-PuseTestcontainers` is set.

## Architecture notes

The recipes are layered on a thin Kotlin extension layer over jetcd, not a wrapper around it:

- **Extensions in `common/`** (`ClientExtensions`, `KVExtensions`, `LeaseExtensions`, `WatchExtensions`, `TxnExtensions`, `ByteSequenceExtensions`, etc.) are where most direct etcd interaction happens. New recipes should compose these rather than reach into jetcd directly.
- **`EtcdConnector`** (`common/EtcdConnector.kt`) is the shared base for stateful recipes. It owns the jetcd `Client`, tracks `startCalled` / `closeCalled` lifecycle (atomic flags), accumulates background-thread exceptions in `exceptionList`, and implements `Closeable`. Recipes generally follow a `start()` … `close()` lifecycle and surface async failures via `exceptions` rather than throwing from a worker thread.
- **`EtcdRecipeException` / `EtcdRecipeRuntimeException`** are the library's own exception types — prefer these over leaking jetcd exception types.
- **Concurrency**: recipes rely on `kotlinx-coroutines`, `kotlin.concurrent.atomics` (stdlib `AtomicBoolean` / `AtomicReference`), and helpers from `com.pambrose.common-utils` (`BooleanMonitor`). Several Kotlin opt-ins are enabled compiler-wide: `kotlin.time.ExperimentalTime`, `kotlin.ExperimentalUnsignedTypes`, `kotlin.concurrent.atomics.ExperimentalAtomicApi` — assume these are available without per-file `@OptIn`.
- **Watcher callbacks** run on jetcd's Vert.x event loop. Anything that needs another gRPC response (or contends with a lock the caller holds while waiting on gRPC) will deadlock the event loop. The `Client.watcher` extension hops callbacks onto a dedicated single-thread executor; recipes should still avoid blocking the callback on locks held by callers waiting on gRPC.

## Tests

- **Thread-based** (`*Tests.kt` under each recipe directory) — N threads in a single JVM simulate distributed clients via `blockingThreads(...)` / `nonblockingThreads(...)` in `etcd-recipes/src/test/kotlin/io/etcd/recipes/common/TestExtensions.kt`.
- **Container-based** (`etcd-recipes/src/test/kotlin/io/etcd/recipes/container/Container*Test.kt`) — each participant runs as its own container coordinating through a shared etcd container. Uses `EtcdContainerNetwork`, `Participant.newContainer(...)`, and `Client.awaitResults(...)` from the `common/` test fixtures. The runners write their outcome to `/test-results/{testId}/{participantId}` as JSON; orchestrators decode the per-recipe payload type via the `ParticipantResult.payload<T>()` extension. Gated by `assumeTrue(testcontainers=true)` so default `./gradlew check` skips them cleanly.

Test JVMs fork per class (`forkEvery=1`) so background threads / etcd watch connections from one spec don't interfere with the next. `maxParallelForks = cores/2` lets multiple test classes run concurrently against the same etcd; each test namespaces its keys by class name to avoid collisions.

## Versioning

The current development branch is named after the version (e.g. `0.10.1`). Library version lives in `gradle.properties` (`version=...`); Kotlin/dependency versions live in `gradle/libs.versions.toml`; bump both when releasing.
