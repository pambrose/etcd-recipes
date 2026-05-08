# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`etcd-recipes` is a Kotlin/Java/JVM client library for [etcd](https://etcd.io) v3, built on top of [jetcd](https://github.com/etcd-io/jetcd). It aims to provide for etcd what Apache Curator provides for ZooKeeper: higher-level distributed coordination primitives (barriers, locks, leader election, service discovery, queues, distributed counters, caches).

## Build, Test, Lint

JDK 17 toolchain (configured in `build.gradle.kts` via `kotlin { jvmToolchain(17) }`). Gradle wrapper is pinned to 9.5.0. Common entry points are in the `Makefile`:

- `make build` — `./gradlew clean build -xtest` (build without running tests)
- `make tests` — `./gradlew check jacocoTestReport` (runs all tests + coverage)
- `make lint` — `./gradlew lintKotlinMain lintKotlinTest`
- `make versioncheck` — `./gradlew dependencyUpdates --no-parallel`
- `make refresh` — refresh dependencies
- `make upgrade-wrapper` — bumps the Gradle wrapper

Run a single test class:
```
./gradlew :etcd-recipes:test --tests "io.etcd.recipes.barrier.DistributedBarrierTests"
```
Tests use JUnit 5 (`useJUnitPlatform()`) plus Kluent assertions; some tests use Kotest. When adding new Kotlin tests, prefer Kotest with `StringSpec()` and an `init {}` block, plus MockK where appropriate.

## Running etcd locally

Tests and examples expect a local etcd at `http://localhost:2379`. Start one with the helper script:
```
./etcd.sh
```
which runs `etcd --listen-client-urls=http://localhost:2379 --advertise-client-urls=http://localhost:2379`. The local data directory `default.etcd/` is gitignored.

## Module Layout

Multi-module Gradle build (`settings.gradle.kts`):

- **`etcd-recipes/`** — the library itself. Source is organized by recipe under `io.etcd.recipes.*`:
  - `barrier/` — `DistributedBarrier`, `DistributedBarrierWithCount`, `DistributedDoubleBarrier`
  - `cache/` — `PathChildrenCache`
  - `counter/` — `DistributedAtomicLong`
  - `discovery/` — `ServiceDiscovery`, `ServiceCache`, `ServiceInstance`
  - `election/` — `LeaderSelector`, `LeaderReporter`
  - `keyvalue/` — `TransientKeyValue`
  - `queue/` — `DistributedQueue`, `DistributedPriorityQueue` (share `AbstractQueue`)
  - `common/` — extension functions over jetcd's `Client`/`KV`/`Lease`/`Watch`/`Txn` and shared base infrastructure (see below)
  - `util/` — small CLI utilities (`ShowKeys`, etc.)
- **`etcd-recipes-examples/`** — runnable Java and Kotlin examples mirroring each recipe; useful as living documentation. Examples are typically `main()` programs that call into the library.

## Architecture notes

The recipes are layered on a thin Kotlin extension layer over jetcd, not a wrapper around it:

- **Extensions in `common/`** (`ClientExtensions`, `KVExtensions`, `LeaseExtensions`, `WatchExtensions`, `TxnExtensions`, `ByteSequenceExtensions`, etc.) are where most direct etcd interaction happens. New recipes should compose these rather than reach into jetcd directly.
- **`EtcdConnector`** (`common/EtcdConnector.kt`) is the shared base for stateful recipes. It owns the jetcd `Client`, tracks `startCalled` / `closeCalled` lifecycle (atomic flags), accumulates background-thread exceptions in `exceptionList`, and implements `Closeable`. Recipes generally follow a `start()` … `close()` lifecycle and surface async failures via `exceptions` rather than throwing from a worker thread.
- **`EtcdRecipeException` / `EtcdRecipeRuntimeException`** are the library's own exception types — prefer these over leaking jetcd exception types.
- **Concurrency**: recipes rely on `kotlinx-coroutines`, `kotlin.concurrent.atomics` (stdlib `AtomicBoolean` / `AtomicReference`), and helpers from `com.pambrose.common-utils` (`BooleanMonitor`). Several Kotlin opt-ins are enabled compiler-wide: `kotlin.time.ExperimentalTime`, `kotlin.ExperimentalUnsignedTypes`, `kotlin.concurrent.atomics.ExperimentalAtomicApi` — assume these are available without per-file `@OptIn`.

## Versioning

The current development branch is named after the version (e.g. `0.9.22`). Library version is defined in `build.gradle.kts`; Kotlin/dependency versions live in `gradle.properties`; bump both when releasing.
