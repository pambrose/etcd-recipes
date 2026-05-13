# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
