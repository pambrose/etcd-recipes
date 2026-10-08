# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (build)

- Kotlin's ABI validation checks every published module's public API against a reference
  dump checked in under `<module>/api/`. `check` and pull-request CI fail when the API
  changes; after an intended change, `make api-dump` updates the dump to commit with it
  (`make api-check` runs the check alone).

### Changed (build)

- Built with Kotlin 2.4.21 (from 2.4.20) and Gradle 9.8.1 (from 9.8.0).
  `etcd-recipes-core` depends on Guava 33.7.2 (from 33.7.1) and common-utils 5.1.1 (from
  5.1.0); the examples and tests use Logback 1.6.5 (from 1.6.4).

### Changed (CI)

- CI's full test run on master leaves out the Lincheck model checks (`-PskipLincheck`).
  They're CPU-heavy, and on a 2-core runner they pushed the suite past its 40-minute
  timeout. They still run locally with `make tests` and `make tests-tc`.
- A zizmor workflow audits the GitHub Actions workflows on every pull request and master
  push; `make zizmor` runs the same zizmor release locally.
- Every action in the workflows is pinned to a commit SHA, with its release in a trailing
  comment, and checkouts no longer leave the git credential on disk
  (`persist-credentials: false`). Dependabot proposes action updates weekly, once a
  release is a week old.

## [0.13.0] - 2026-09-30

A reliability release. Every issue from a full review of the library
([docs/CODE_REVIEW_2026-09-28.md](docs/CODE_REVIEW_2026-09-28.md)) is fixed, the recipes'
distributed protocols are model-checked with TLA+ (`specs/`), and their in-memory
concurrent state with Lincheck. [RELEASE_NOTES.md](RELEASE_NOTES.md) has the highlights.

### Upgrading from 0.12.0

Most fixes need nothing from you. These change what you depend on, what you see, or how
several processes must be deployed together; details are in the sections below.

**Build and dependencies**

- `etcd-recipes-core` no longer brings a logging backend. The published POMs depend on the
  SLF4J API alone, so add one (Logback, Log4j 2, …) if your application relied on the
  `logback-classic` it used to pull in. jetcd and kotlinx-serialization are now
  compile-scope dependencies, as the public API needs.

**Deploy all members together**

- A counted barrier's waiting keys moved under `<path>/waiting/<round>/`. The two layouts
  don't count each other's waiters, so every `DistributedBarrierWithCount` and
  `DistributedDoubleBarrier` member meeting at one path must run the same version.
- A read-write lock downgrade carries its rank in the entry value; avoid downgrading while
  a mixed-version fleet shares a lock. A `clientId` starting with `rank:` is rejected.

**Behavior changes**

- The lock recipes (`DistributedMutex`, `DistributedReadWriteLock`, `DistributedSemaphore`)
  default to a 10-second lease, up from 2. A crashed holder's lock takes up to 10 seconds
  to free; pass `leaseTtlSecs` to choose.
- Plain writes (`putValue`, `deleteKey`, `deleteChildren`, `compact`, and their suspending
  twins) make one attempt instead of being retried, and every RPC failure surfaces as
  `EtcdRecipeRuntimeException` with the original failure as its cause (a non-retriable one
  used to escape as a raw `ExecutionException`).
- Listener callbacks (background-exception, connection-state, lock- and permit-lost) run
  on a per-recipe notifier thread, in order, never on jetcd's event loop or the reporting
  thread. A `LOST` from a stream that's gone for good sticks until the recipe restarts.
- `exceptions` keeps the most recent 100 failures (`droppedExceptionCount` counts the rest).
- `ping()` makes a single attempt bounded at 2 seconds (`RpcResilience.PROBE`), and a
  definite refusal from etcd counts as reachable.
- `ServiceDiscovery.queryForNames()` returns each service name once, instead of every
  instance's full key.
- `EtcdTlsConfig` refuses a client certificate without its key, or the reverse
  (`IllegalArgumentException`, which fails a Spring app's startup).
- The Ktor plugin closes the client it owns on `ApplicationStopped`, after your
  `ApplicationStopping` teardown.
- Micrometer's `etcd.cache.entries` gauges carry a `recipe` tag.

**Source changes**

- `EtcdLock` gained `fencingToken`; an implementation outside this library must add it.
- The bounded suspending `withLock(timeout) { … }` requires a non-null result type
  (`T : Any`).
- `ServiceCacheListener.cacheChanged`'s third parameter is `instanceKey`, and
  `ServiceCacheEvent.serviceName` is deprecated in favor of `instanceKey` (both hold
  `<serviceName>/<id>`).

### Added (fencing tokens)

- `EtcdLock.fencingToken` (on `DistributedMutex` and both of `DistributedReadWriteLock`'s
  views) and `DistributedSemaphore.fencingToken`: a number etcd assigned to the calling
  thread's hold, larger than any earlier conflicting holder's, or -1 when it holds nothing.
  A downstream resource that keeps the largest token it has seen and refuses smaller ones
  turns away a holder that lost the lock (a pause past its lease) but hasn't noticed yet.
  A lock notices a loss only on the client, after etcd has already granted the lock to
  the next waiter. The mutex's token is its grant revision; the read-write lock's and the
  semaphore's are their entries' create revisions.

### Added (suspending twins)

- Suspending twins for the blocking calls added after the coroutine layer:
  - `LeaderLatch.awaitStart` / `awaitLeadership`
  - `awaitStart` for `LeaderObserver`, `NodeCache`, and `TypedTransientKeyValue`
  - `TypedPathChildrenCache.awaitStart` / `awaitStartComplete`
  - the typed queues' `receive` / `awaitTryDequeue` / `awaitEnqueue`
  - `ServiceProvider.awaitStart` / `awaitGetInstance` / `awaitGetAllInstances`
  - the work queue's `awaitDeadLetters` / `awaitRequeueDeadLetter` /
    `awaitPurgeDeadLetter`

### Added (suspend holders and lock loss)

- On a lock or semaphore built with `interruptOnLockLoss` / `interruptOnPermitLoss`, losing
  the hold now cancels the suspending `withLock` / `withPermit` body. The call then throws
  the new `HoldLostException` (an `EtcdRecipeRuntimeException`). For a semaphore, only the
  holder whose permit was lost is affected. Before, the option interrupted an idle
  confined thread (locks) or an unrelated pooled thread (semaphores), and the body never
  learned of the loss.

### Added (`DistributedAtomicLong`)

- `withDistributedAtomicLong` takes a `resilience` parameter, like the constructor.
- `DistributedAtomicLong`'s KDoc now states that an update that throws has an unknown
  outcome (its transaction may have been applied), so blindly retrying it can count
  twice.

### Added (TLA+ specifications)

- `specs/` holds TLA+ specifications of the counted barrier's round protocol, the
  read-write lock's admission, and the work queue's claims, model-checked by TLC with
  `make tla` and in CI. Each models the code one RPC per action, with leases expiring
  between them, and was checked against the bugs it's meant to catch.

### Added (tests)

- Lincheck model-checking tests for in-memory concurrent state: the provider strategies,
  and `EtcdConnector`'s connection state and recorded exceptions. The strategy test found
  the `StickyStrategy` race above.

### Changed (RPC engine: real jetcd failures, reads-only retries)

- The retry check only recognized jetcd's `EtcdException`, but jetcd's KV, lease, and
  lock calls fail with raw gRPC `StatusRuntimeException`s — so status-based retries never
  fired; only attempt timeouts were retried. gRPC statuses (`UNAVAILABLE`, `INTERNAL`,
  `DEADLINE_EXCEEDED`) now count.
- **Only calls that are safe to repeat are retried**: reads, plus `unlock` and
  `leaseGrant`, whose duplicates are harmless. Plain writes — `putValue`, `deleteKey`,
  `deleteChildren`, `compact` (and their suspending twins) — now make one attempt bounded
  by `operationTimeout`, like transactions: a write that failed or timed out may still
  have been applied, and a retried attempt could land after a newer write and revert it.
  Previously a timed-out write was retried.
- **Every RPC failure now surfaces as `EtcdRecipeRuntimeException`** with the original
  failure (gRPC status, timeout, or interrupt) as its cause. Non-retriable failures used
  to escape as a raw checked `ExecutionException` — undeclared, and uncatchable as such
  from Java.
- Interrupts are handled consistently: one arriving during a retry backoff or while
  awaiting a transaction now surfaces as `EtcdRecipeRuntimeException` with the thread's
  interrupt flag restored (it used to escape as a raw `InterruptedException` with the
  flag cleared), and `leaseRevoke` no longer clears the interrupt flag of an interrupted
  caller.

### Changed (lock lease TTL)

- **Behavior change:** `DistributedMutex`, `DistributedReadWriteLock`, and
  `DistributedSemaphore` default to a 10-second lease (`leaseTtlSecs`), up from 2 seconds.
  A lapsed lease loses the lock, and at 2 seconds a GC pause or network blip of about 1.3
  seconds could put two holders in the critical section until the first noticed. The
  trade-off: a crashed holder's lock now takes up to 10 seconds to free. An explicit
  `leaseTtlSecs` is unaffected, and the other recipes keep 2 seconds.

### Changed (barriers: wire layout)

- **Wire-layout change:** a counted barrier's waiters register under
  `<path>/waiting/<round>/`, where the round is `/ready`'s create revision, instead of
  directly under `<path>/waiting/`. The two layouts don't count each other's waiters, so
  every member meeting at one path, including `DistributedDoubleBarrier` members, must run
  the same version.

### Changed (discovery naming)

- **Behavior change:** `ServiceDiscovery.queryForNames()` (and `awaitQueryForNames()`)
  returns each service name once, in key order. Before, it returned the full etcd key of
  every instance (`…/names/worker/AbC1234`), one per instance.
- `ServiceCacheListener.cacheChanged`'s third parameter is renamed `serviceName` →
  `instanceKey`, since it holds `<serviceName>/<id>`. `ServiceCacheEvent.serviceName` is
  likewise now `instanceKey`; `serviceName` remains as a deprecated alias.

### Changed (coroutines)

- **Source-incompatible:** the bounded suspending `withLock(timeout) { … }` now requires
  its body to return a non-null type (`<T : Any>`), so a `null` result always means "not
  acquired". Before, a body that returned `null` was indistinguishable from a timeout.
  Wrap a nullable result if you need one.

### Changed (examples and docs)

- The runnable examples use only the standard library, etcd-recipes, and kotlin-logging.
  They used to import sleep, thread, and random helpers from Guava and common-utils, which a
  project copying an example doesn't have. The examples module no longer depends on either
  library.
- Documentation drift fixed:
  - CLAUDE.md no longer pins a stale Gradle version or claims Kluent assertions.
  - `make docs-check` is described as what CI *checks*: `ci.yml` compiles the snippets and
    `docs.yml` builds the site.
  - The README's lint command runs detekt too.
  - `llms.txt` says which recipe areas have Java examples.
  - References to a nonexistent `./etcd.sh` / `make etcd` now name `etcd-start.sh` /
    `make etcd-start`.

### Changed (tests and CI)

- The counted barrier's watcher tests wait until the waiter is parked before injecting a
  peer, instead of sleeping 2 seconds. On a slow runner the sleep could let the pre-park
  recheck see the peer first, so the watcher branch the test targets never ran; with that
  branch disabled, the test now fails. The barriers are closed with `use`.
- The multi-container election test detects overlapping terms. Each term claims an
  "active leader" key in etcd for its duration and reports whether it found the key taken;
  before, the test checked only that every candidate took and released leadership.
- CI: `ci.yml` runs with `permissions: contents: read`, and its actions move to the Node 24
  generation (`checkout@v7`, `setup-java@v6`, `setup-gradle@v6`, `codecov-action@v7`,
  `upload-artifact@v7`). `setup-gradle` v6's caching is a proprietary component under
  Gradle's Terms of Use.
- The documentation site deploys only after CI passes on a master push (a `workflow_run`
  trigger building the commit CI verified). Before, every push to master deployed, even
  when the snippets it embeds no longer compiled.

### Changed (build)

- Built with Kotlin 2.4.20 (from 2.4.10) and Gradle 9.8.0, on a JDK 17 toolchain. jetcd is
  0.8.7 (from 0.8.6). The Ktor plugin targets Ktor 3.6.x (from 3.5.x), and the other
  satellites track Spring Boot 4.1.1, Micrometer 1.17.1, and Jackson 2.22.3.

### Fixed (packaging: dependency scopes)

- The published `etcd-recipes-core` POM declared jetcd and kotlinx-serialization at
  `runtime` scope although both appear in the public API (every recipe takes a jetcd
  `Client`; `EtcdCodec` exposes `Json`/`KSerializer`), so a project that added only
  `etcd-recipes-core`, as the README says, failed to compile. Both are now `api`
  (`compile` scope in the POM), alongside kotlinx-coroutines.
- Every published artifact forced `logback-classic` onto consumers — clashing with
  Log4j 2 and other SLF4J backends (a Spring Boot app on `spring-boot-starter-log4j2`
  could fail to start) — and each satellite also dragged in Guava and common-utils. The
  libraries now depend on the SLF4J API alone; the satellites' POMs list only the core
  artifact and their own framework.
- Documented that Kotlin callers need Kotlin 2.3 or newer.

### Fixed (lease grants and registration errors)

- Self-healing leases (service registrations, barriers, election participation,
  `TransientKeyValue`) are granted and revoked under the recipe's own RPC budget:
  `selfHealingKeepAlive()` takes an `rpc` parameter. A registration against an unreachable
  etcd used to sit through 5 × 30 s before reporting anything.
- An establish hook that throws part-way no longer leaves its lease held until the TTL runs
  out; the lease is revoked.
- `ServiceRegistry` reports a lost CAS (the key already exists) separately from an
  infrastructure failure, and both carry their cause. A declined establish throws the new
  `EstablishDeclinedException`, and `EtcdRecipeException` takes an optional cause.

### Fixed (lease healing and registration lifecycle)

- A heal no longer re-grants a lease that is still alive in etcd. jetcd reports a lease
  "gone" from its own client-side deadline; after an etcd leader change the new leader
  extends every lease, so the lease and its keys can outlive that report. Re-granting
  then made the establish CAS lose to the recipe's own key and the old lease lapsed, so a
  `ServiceRegistry` instance, barrier, or election participant was permanently lost
  after the cluster recovered. The healer now asks etcd first and, if the lease is alive,
  resumes renewing it (`LeaseEvent.Restored` with the same old and new id).
- A heal whose establish hook throws now revokes the lease it granted, as the initial
  establish already did, instead of leaving a key bound to a lease nobody renews.
- `ServiceRegistry.close()` releases every registration even when one instance's cleanup
  delete fails (etcd unreachable at shutdown): it used to throw at the first failure and
  leave the remaining instances renewing their leases inside a closed registry. The
  cleanup delete now runs under the registry's RPC budget.
- Re-registering an instance whose key had vanished no longer leaks the previous
  registration's keep-alive and healer thread.
- `DistributedBarrier.setBarrier`, `DistributedBarrierWithCount.waitOnBarrier`, and
  `LeaderSelector` participation no longer report an infrastructure failure (a refused
  or failed lease grant) as a lost CAS; it propagates with its cause.
- `keepAlive(lease, onKeepAliveError)` now calls `onKeepAliveError` only when renewal
  actually stopped (the stream completed, or etcd reported the lease not found). A
  transient stream error — which jetcd restarts itself, with renewal continuing — is
  logged at warn instead of reported as a lost lease.

### Fixed (RPC budgets and probes)

- The recipe's `RpcResilience` (timeout, retries, and metrics) now reaches every RPC it
  makes. Several calls fell back to the default (5 × 30 s, uninstrumented):
  - `ServiceDiscovery.queryForNames` / `queryForInstances`, which did so while holding
    the discovery monitor;
  - the cache from `serviceCache(name)`, which now inherits the discovery's config;
  - `ServiceProvider.getAllInstances`;
  - `LeaderSelector`'s leadership lease revoke, which could delay `close()` for 30 s
    during a partition.

  `getChildrenValues`, `deleteKeys`, `putValuesWithKeepAlive`, and
  `LeaderSelector.getParticipants` take a trailing `rpc` parameter.
- `putValuesWithKeepAlive` / `putValueWithKeepAlive` now revoke their lease when the block
  ends (returns or throws), so the keys go with the block instead of living up to a TTL
  longer. The keys are written in one transaction, so a reader never sees half of a
  multi-key registration.
- `Client.ping()` and a recipe's `ping()` now make a single attempt bounded at 2 seconds
  (the new `RpcResilience.PROBE`) instead of retrying for about 2.5 minutes during an
  outage. A definite refusal from etcd (`PERMISSION_DENIED`, `NOT_FOUND`, …) counts as
  reachable, so a prefix-scoped RBAC user no longer reads as permanently down. Recipes
  gain `ping(rpc)`.
- Spring Boot: the health indicator probes with `etcd.recipes.health.timeout` (default
  2 s), and `management.health.etcd.enabled=false` now switches it off. Before, every
  `/actuator/health` call could hang for minutes during an etcd outage.
- Micrometer: `bindQueueDepth` / `bindAvailablePermits` read with `RpcResilience.PROBE`
  (overridable through a new `rpc` argument), so during an outage they report `NaN`
  promptly instead of holding the scrape past its timeout. `AbstractQueue.size(rpc)` and
  `DistributedSemaphore.availablePermits(rpc)` are the new accessor overloads.

### Fixed (notifications and connection state)

- Listener callbacks no longer run on jetcd's event loop. `BackgroundExceptionListener`s,
  `ConnectionStateListener`s, and lock-lost / permit-lost listeners (with the opt-in
  interrupt) now run on a per-recipe notifier thread, one at a time and in report
  order. Before, a lock's lease loss ran them inline on jetcd's lease callback thread,
  so a listener that blocked or made an RPC stalled keep-alive processing for every
  lease on the client. `recordException` never blocks.
- `connectionState` no longer hides a dead stream. A `LOST` from a stream that is gone
  for good (watch recovery or lease healing abandoned, or a failed start) now sticks
  until the recipe restarts, so a later `RECONNECTED` from another, healthy stream can't
  clear it. Before, `isHealthy()` could report true with the recipe's participation or
  registration permanently gone. State changes are also delivered in the order they
  happened, and `connectionStateAsFlow` registers before it reads, so it no longer
  misses a change.
- `connectionState` now leaves `SUSPENDED` after jetcd recovers a stream by itself. A
  transient watch error is followed by `WatchRecoveryEvent.Resubscribed`, and a transient
  keep-alive error by `LeaseEvent.Restored` with the same old and new id (self-healing
  leases, lock and permit leases, and leadership leases). Before, one network blip left a
  mutex `SUSPENDED` for life. Lock and permit leases also report their real lease id
  (it was `-1`) and record keep-alive metrics.
- `LeaderLatch` and `ServiceProvider` report the health of the recipes they wrap. A
  standby latch whose participation lease can't heal, or a provider whose cache watch was
  abandoned, now reads `LOST` / unhealthy, and their failures reach the wrapper's
  `exceptions` as they happen, not only when a term ends.
- `close()` from a cache listener (the watch dispatcher) or a lease listener (the healer)
  no longer stalls 5 s waiting on its own thread.
- `exceptions` keeps the most recent 100 failures. The new `droppedExceptionCount` counts
  the rest, so a long-lived recipe's list no longer grows without bound.
- `lockLostAsFlow` / `permitLostAsFlow` never block their notifier (a truly unlimited
  buffer). A bounded `backgroundExceptionsAsFlow(capacity)` drops the oldest failures
  instead of blocking the recipe's notifications.

### Fixed (resilient watcher revisions)

- A compaction whose resync fails is no longer forgotten. The next recovery attempt
  resyncs again instead of resubscribing at the compacted revision and reporting
  `Resubscribed` with nothing reconciled. A recovery whose new stream dies before
  delivering anything keeps spending the same retry budget instead of restarting it, so a
  bounded `WatchResilience` does reach `Failed`.
- An un-anchored watch (no start revision) now resumes from the revision it was created
  at, so writes committed while it was recovering are replayed rather than lost. An
  anchored watch no longer jumps its resume point to the created notification's revision
  before the replay of older events has finished. The watcher requests the created
  notification internally and hides it from the watch block unless the caller asked for
  it.
- Without `resyncWith`, a compaction now resumes the watch at the compacted revision (the
  oldest etcd still serves) rather than one past it, which skipped that revision's events.
- Closing the `Client` while a watcher is open now ends its recovery with
  `WatchRecoveryEvent.Failed` instead of retrying forever, silently, every few seconds.
  Each failed recovery attempt is logged at debug.

### Fixed (Java interop, logging context, and leftovers)

- Java can construct a `DistributedPriorityQueue` and set `LeaderLatch`'s
  `closeJoinTimeout`. A Kotlin `Duration` parameter hides a member from Java, and those were
  the only forms. New `(long, TimeUnit)` overloads:
  `DistributedPriorityQueue(client, path, wait, unit[, resilience])`,
  `EtcdRecipes.distributedPriorityQueue(path, wait, unit)`, and a `LeaderLatch` constructor
  ending in `closeJoinTimeout, unit`. `EtcdRecipes.distributedPriorityQueue(path)` is now
  callable from Java too. A Java source file in the test source set references each of
  them, so CI's compile catches a Java-hidden API.
- Background logs are attributable. A watcher (`Client.watcher` / `withWatcher`) and a lease
  healer (`selfHealingKeepAlive`) now run every callback, recovery attempt, and heal with the
  MDC of the code that created them. Every recipe creates them under its
  `etcd.recipe` identity, so the watch blocks and recovery handlers of the caches, service
  cache, observer, barriers, queues, locks, registry, and `TransientKeyValue` now log with it.
  So do the listeners they call. The work queue's sweeper runs under it too, and its thread
  is named `workqueue-sweeper[<queue path>]`.
- `DistributedReadWriteLock` no longer uses a fully qualified
  `java.util.concurrent.atomic.AtomicReference`. A detekt `ForbiddenImport` rule now rejects
  `java.util.concurrent.atomic` imports.
- A counted-barrier wait that times out no longer makes a second, un-guarded delete of its
  waiting key; an etcd error there used to turn the timeout (`false`) into a throw.
- `LeaderSelector` uses the shared `ElectionPaths` key scheme instead of its own copy.

### Fixed (lock lifecycle and semantics)

- `close()` no longer races an acquisition in flight on `DistributedMutex`,
  `DistributedReadWriteLock`, or `DistributedSemaphore`. It now aborts waits before
  draining holds, and an acquisition re-checks `close()` after registering and after
  winning. Before, one that was granting its lease when `close()` ran could still acquire
  afterward, leaving a closed recipe holding the lock with a live keep-alive, or returning
  `true` for a lock `close()` had already released.
- `DistributedMutex` retries only failures that can heal: lease death, "no leader", and
  retriable RPC statuses. Anything else, such as permission denied on the lock path, is
  thrown as `EtcdRecipeRuntimeException`. `lock()` used to retry it forever (four lease
  grants a second), and `tryLock` reported it as a timeout.
- `DistributedSemaphore.release()` gives up a live permit the calling thread acquired,
  else one it lost (returning `false`), and only then falls back to any permit. Before, a
  thread whose permit was lost released another thread's live permit, admitting one more
  holder than the semaphore allows. `withPermit` releases its own permit exactly.
- `tryLock` / `tryAcquire` deadlines now bound the lease grant, reads, transactions, and
  pauses of the attempt, not just the wait. The abort's revoke gets one short attempt.
  During an etcd brownout a `tryLock(500.milliseconds)` returns `false` close to its
  timeout instead of about two minutes later.
- A read-write-lock or semaphore release retries its revoke, so one lost revoke no longer
  leaves the entry blocking every successor until its lease TTL runs out.
- `DistributedMutex.lock` / `tryLock` and `DistributedSemaphore.acquire` / `tryAcquire`
  declare `InterruptedException` (`@Throws`), so Java can catch it.
- A lock-loss event applies only to the hold it belongs to, and hold counts are read
  safely across threads. A stale event could remove a newer hold of the same thread.

### Fixed (read-write lock: downgrade, sibling paths, clientId)

- A write→read downgrade deadlocked when another process's writer had queued behind
  the write hold: the new read entry waited on that writer, which waited on the write
  hold the downgrading thread could not release. A downgraded read entry now keeps the
  write entry's place in line (it carries the write's rank in its value), so it is
  admitted at once and the queued writer keeps waiting until the downgraded read is
  released too. A downgrade from a write entry that has already vanished server-side
  retries as an ordinary read instead of taking a place it no longer holds. Clients
  from earlier versions do not honor the carried rank, so avoid downgrading while a
  mixed-version fleet shares a lock. Because the rank rides in the entry value, a
  `clientId` starting with `rank:` is now rejected.
- The conflict scan read the lock path without a trailing `/`, so a lock also counted
  the entries of any sibling lock whose path shared its string prefix (`/order-1` vs
  `/order-10`) — false contention, and a self-deadlock for a thread holding one while
  taking the other. It now reads only the lock's own entries.
- Entries were classified by their last path segment, so a writer whose `clientId`
  contained `/` was invisible to readers, letting a reader and a writer hold at once
  (and misreporting `isLocked`). Entries are now classified by their name under the
  lock path.

### Fixed (read-write lock, found by its TLA+ spec)

- A downgraded read's fencing token no longer fences out the writer queued behind it. It
  was the read entry's own, newer revision, so the writer admitted after the downgrade got a
  smaller token than one already issued, and a resource keeping the largest token refused a
  legitimate writer. A hold's token is now its rank: a downgrade's is the write hold's.
- An acquisition whose entry's lease had just expired (not yet noticed) is no longer
  admitted. The conflict scan never checked for the attempt's own entry, so such a client
  could be admitted with no place in line, alongside a writer admitted after the expiry. It
  now starts over at the tail, as the semaphore already did.

### Fixed (`LeaderSelector` candidacy)

- `LeaderSelector` now runs every election attempt and its term on one thread (the
  selector's executor), and the leader-key watch only signals it. That fixes three
  problems:
  - `close()` now waits for a term won through the watch, as it already did for one won at
    `start()`. That term used to run on the watch dispatcher, so `close()` returned after 5
    s while the node still held the leader key and its keep-alive.
  - A step-down can't start a second term while the first is still unwinding. A replayed
    deletion used to start one concurrently on the dispatcher, invisible and unstoppable.
  - An election attempt that fails rather than loses (a refused lease grant, a transaction
    that timed out during an etcd blip) is retried, paced by the watch `RetryPolicy`, and
    its lease is revoked. It used to be logged and dropped, and with the leader key already
    gone no deletion would ever trigger another attempt, so the election could stay
    leaderless.
- `LeaderSelector.start()` can no longer hang. The watch and participation tasks run on
  internal threads, so a user executor needs only one free thread (it hung with fewer than
  three). A watch that can't be set up (a closed client, an unreachable etcd) makes
  `start()` throw; it used to hang or, on a closed client, return a selector that never ran.
  A `start()` rejected by a shut-down executor leaves the selector closable, and an
  interrupted `start()` throws with the interrupt flag restored.
- The leader-key watches of `LeaderSelector`, `LeaderObserver`, and `leadershipAsFlow` are
  anchored just past the read that precedes them, so a hand-off during setup is no longer
  missed. It could leave a candidate standing by forever, or an observer showing a stale
  leader until the next hand-off. `leadershipAsFlow` also re-reads after a recovery only
  when events could have been missed, like `LeaderObserver`.

### Fixed (election lifecycle)

- `LeaderSelector.waitOnLeadershipComplete(timeout)` now honors its timeout. It first
  waited, untimed, for the start worker to finish, which only happens when the candidacy
  ends — so a standby's timed wait blocked until it won and finished a term, or was
  closed. The coroutine `awaitLeadershipComplete(timeout)` inherited the same bug.
- `LeaderSelector.close()` called from inside `takeLeadership` no longer deadlocks.
  `close()` waited for the start worker, which was the calling thread when this node
  won at `start()`.
- A `LeaderSelector` closed without ever winning (or whose start worker failed) can be
  started again; `start()` used to throw "Previous call to start() not complete". A
  restart also resets `connectionState`, so it no longer reports the previous candidacy's
  `LOST`.
- `close()` on a `LeaderSelector` that was never started no longer throws "start() not
  called", matching `LeaderLatch` and `LeaderObserver`. A `withLeaderSelector { }` block
  that never started it used to throw out of `use`.
- `LeaderObserver` no longer replays `takeLeadership` for the current leader after every
  watch recovery, only when events could have been missed (a resync, or a resubscribe
  that could not resume at a known revision). A failure re-reading the leader there now
  reaches `LeaderListener.onError` and `exceptions` instead of being swallowed.
- `DistributedDoubleBarrier` now passes its `clientId` to its enter and leave barriers;
  it was accepted but never used.

### Fixed (barriers: close() cancels in-flight waits)

- `DistributedBarrierWithCount.close()` now cancels an in-flight `waitOnBarrier` cleanly
  (it returns `false`) wherever the waiter has got to. Previously a `close()` that
  landed before the waiter parked either made `waitOnBarrier` throw — a cause-less
  `EtcdRecipeException("Failed to set waitingPath")` during the ready CAS or lease
  grant, or `EtcdRecipeRuntimeException("close() already called")` from its internal
  reads — or went unseen, leaving the waiter parked until its timeout. `close()` also
  cancels every concurrent waiter on the instance, not only the most recent one, and a
  genuine waiting-key CAS failure now carries its cause.
- `DistributedBarrier.close()` now releases a thread parked in `waitOnBarrier` (it
  returns `false`) instead of leaving it to its timeout, and a `close()` during the
  waiter's watch setup no longer makes it throw `close() already called`.

### Fixed (barrier rounds and removal)

- `DistributedBarrierWithCount` releases nobody until the round's release is committed.
  Before, the member that saw the count reached removed its own waiting key and left
  first, then deleted `/ready` in a single, un-retried transaction. If that delete failed
  during an etcd blip, the tripper had already gone (or threw), and every other waiter saw
  `/ready` still standing and parked until its timeout, forever for `waitOnBarrier()`. Now
  `/ready` is deleted first, guarded on the round and retried on a transient failure. A
  delete that still can't be committed is recorded in `exceptions`, and the member stays
  parked with the rest. A failed read on the watch thread is recorded too, instead of only
  logged.
- `DistributedBarrierWithCount` counts one round at a time. Before, every key under
  `waiting/` counted, so a member that looped straight back into `waitOnBarrier()` (or
  arrived just after a trip) could trip the next round alone on keys the last round hadn't
  cleaned up yet. `waiterCount` likewise counts only the round in progress.
- `DistributedBarrier.setBarrier()` after `removeBarrier()` on the same instance sets the
  barrier again. Before, the removal flag was permanent, so the second `setBarrier()`
  returned `false` (read as "another client holds it") and left no barrier. The flag is now
  per `setBarrier()`, and is set before the healer closes, so a heal racing a removal can't
  re-arm the barrier. A new `setBarrier()` also retires the previous one's healer instead of
  leaking it.

### Fixed (queues: items stay in their queue and are never overwritten)

- A consumer parked on an empty `DistributedQueue` or `DistributedPriorityQueue` could
  take — delete and return — an item from a *different* queue whose path shares its
  string prefix (a take on `/jobs` stealing from `/jobs2/…` or `/jobs-retry/…`). The
  wait now watches only the queue's own children.
- Queue item keys were the enqueue millisecond plus 3 random characters, written with
  an unconditional put, so two enqueues in the same millisecond could silently
  overwrite one another. Keys now carry a 16-character random suffix and are created
  only if absent (retrying with a fresh key), in `enqueue`, `enqueueAll`, and the
  work queue's delayed-item promotion.
- Enqueue writes are no longer retried. A retried put whose first attempt had in fact
  landed could re-create an item that a consumer had already taken; an ambiguous
  failure now reaches the caller instead.
- `DistributedWorkQueue.enqueue(value, delay)` rejects an infinite delay, which used to
  overflow into a key that made every receive on the queue throw. A delayed key whose
  ready time cannot be parsed is now moved to the dead-letter space (and recorded)
  rather than breaking receives.

### Fixed (queue lifecycle and delivery)

- `DistributedWorkQueue`: `maxDeliveries` now caps redelivery through `WorkItem.requeue()`
  too. It was checked only when a crashed consumer's claims were reclaimed, so a poison
  message whose handler called `requeue()` (the README's canonical consumer) came back
  forever, and a single-consumer deployment made no progress. The next receive now
  dead-letters an item that has already been delivered `maxDeliveries` times.
- `DistributedWorkQueue`: a receive no longer retries a dead consumer lease without
  limit. It now throws once lease healing is abandoned (a bounded or disabled
  `LeaseResilience`) or the queue is closed. A bounded `receive(timeout)` returns `null`
  at its timeout while a heal is still in progress. It used to spin at 4 Hz, three RPCs
  per pass, past its timeout and past `close()`.
- `DistributedWorkQueue.close()` releases a parked `receive()`, which then throws. It
  used to stay parked, and once an item arrived it created the consumer lease (keep-alive,
  healer, and sweeper) after close. It then claimed an item that could never be acked,
  and that nothing would ever reclaim, until the `Client` closed. A close that races the
  lease's creation now releases the new lease instead of claiming under it.
- `DistributedQueue` / `DistributedPriorityQueue`: `close()` releases a parked
  `dequeue()` or `poll()`, which then throws, the way `close()` already released barrier
  waiters. The take used to park until an item arrived, and then deleted it and handed it
  to the closed instance.
- `DistributedWorkQueue`'s empty-queue wait is anchored at the revision the queue was
  seen empty. An item enqueued while the watch was being established was not delivered
  until the next sweep interval (30 s by default), so `receive(10.seconds)` could return
  `null` with an item in the queue. The other queues were fixed the same way in 0.12.0.
- `DistributedWorkQueue`'s reclaim-sweep failures now reach `exceptions` and the
  background exception listener, once per failing streak. They were logged at DEBUG and
  dropped, so a persistent failure silently disabled background reclaim.
- `WorkItem.ack()` and `requeue()` are guarded on the specific claim, not just the
  consumer instance. After a claim lapsed and the same instance received the item again,
  the earlier item's `ack()` used to return `true` and delete the new claim.

### Fixed (queue ambiguity, ordering, and cost)

- A work-queue claim whose transaction response was lost after it committed is now
  reconciled. The consumer re-reads the claim marker and, if the claim is its own, returns
  the item. It used to throw, leaving the claim stranded on the consumer's healthy lease,
  where no sweep would reclaim it until the instance closed. The plain queues' docs now
  say that a take that fails may still have consumed the item.
- `DistributedQueue`'s take picks the lowest key among the entries at the head's revision,
  so an `enqueueAll` batch keeps argument order whatever etcd's sort does with equal
  revisions.
- Head selection is cheaper. The priority queue (and every key-ordered first-child read)
  no longer asks etcd to sort, so etcd can stop at the first key instead of reading the
  whole prefix. `DistributedQueue` finds its head with a keys-only read.
- The work queue's orphan sweep diffs one keys-only read of `claimed/` against one of
  `claims/`, and fetches payloads only for orphans. It used to issue a transaction for
  every claim in flight on every empty receive: with 50 idle consumers and 50 items in
  flight, about 2,500 transactions per enqueue. A receive that finds the queue empty
  sweeps at most once a second per instance.
- Queue metrics now cover enqueues (all queues), `tryDequeue`, and the work queue's
  `receive`, `ack`, and dead-lettering, as the `etcd.queue` docs described. Before, only
  `dequeue` and `poll` were recorded.
- The typed `putValue` / `getValue` extensions have `@JvmOverloads`, and the misleading
  `TypedTransientKeyValue.start()` KDoc is corrected.

### Fixed (work queue claims, found by its TLA+ spec)

- A claim whose transaction got no answer is no longer reconciled into another thread's
  claim. Threads sharing one `DistributedWorkQueue` share its clientId and lease, and
  `reconcileClaim` recognized its own claim by exactly those, so a thread could be handed
  the item another thread had just claimed: both processed it under one claim, and the
  second `ack()` returned false for a claim that was never lost. Each claim marker's value
  is now `<clientId>:<nonce>`, unique to its attempt, and the item's guards and the
  reconciliation compare against it.
- A claim whose response was lost and whose re-read failed too no longer holds its item
  until the consumer restarts or its lease lapses (possibly never, under a healthy lease).
  The consumer remembers it, per claim attempt (so another thread's unresolved attempt on
  the same item can't displace it), and its sweeper releases it if it committed, giving the
  item back to the queue with that delivery undone, as `unclaim()` does.

### Fixed (cache event path)

- A primed `PathChildrenCache` start (`BUILD_INITIAL_CACHE` / `POST_INITIALIZED_EVENT`)
  that can't load its snapshot now fails instead of reporting a healthy, empty cache that
  would never update. `start()` (with the default wait) and `waitOnStartComplete()` throw
  `EtcdRecipeRuntimeException` carrying the cause, `connectionState` moves to `LOST`, and
  no `INITIALIZED` fires. Before, the failure was only recorded: no watch was ever
  created, `isHealthy()` stayed true, and `POST_INITIALIZED_EVENT` listeners received an
  empty snapshot and concluded the prefix was empty.
- `INITIALIZED` now fires before the watch starts, so it precedes every child event. Every
  listener receives the same immutable snapshot. Before, events the anchored watch
  replayed could arrive before `INITIALIZED`, and each listener got its own later copy of
  the map, so a listener doing `state = initialData` could revert to a stale value for
  good.
- An `INITIALIZED` listener that calls `rebuild()`, `clear()`, or `close()` no longer
  deadlocks `start()`, which used to hold the cache monitor while waiting for the
  listener.
- A compaction resync now tells listeners what changed during the gap. `PathChildrenCache`
  fires `CHILD_REMOVED` / `CHILD_ADDED` / `CHILD_UPDATED`, `ServiceCache` fires
  `DELETE` / `PUT`, and `NodeCache` fires `CREATED` / `UPDATED` / `DELETED`, and so do
  their `eventsAsFlow` flows. Before, the maps converged silently, and state derived from
  events stayed wrong indefinitely.
- `PathChildrenCache.rebuild()` no longer permanently undoes a concurrent watch event. A
  snapshot older than an event the watch has already applied is re-read rather than
  applied. Before, a child deleted while the rebuild's snapshot was in flight was put
  back and stayed in `currentData` forever.
- `TypedPathChildrenCache` delivers each event to every typed listener even when one
  throws, as the untyped cache does. A snapshot child that can't be decoded is left out
  of `INITIALIZED` instead of suppressing it for everyone. Failures still reach
  `untyped.exceptions`.

### Fixed (discovery robustness)

- One malformed or newer-schema instance entry no longer breaks discovery for a whole
  service. Before, a non-JSON value under `names/<svc>/` (or JSON with a field this version
  didn't know) made `ServiceCache.instances`, `queryForInstances`, and every
  `ServiceProvider.getInstance()` throw. Now the entry is skipped, logged, and recorded in
  `exceptions`, and unknown fields are ignored. The cache decodes each entry once, on
  arrival, instead of on every read and once per listener. An entry it held that is
  overwritten with something unreadable is dropped, and listeners get a `DELETE`.
- `ServiceProvider.noteError` counts errors within a `downPeriod` window from the first.
  Before, the count never reset, so an instance with one sporadic failure a day was ejected
  every third day. The provider also forgets instances that are no longer registered or
  whose window has lapsed. Before, every instance that ever had an error kept an entry until
  `close()`. Ejection updates are atomic per instance, so a cleanup can't drop an ejection
  another thread has just made.
- `ServiceDiscovery` no longer keeps every cache and provider it ever handed out; closed
  ones are dropped.
- `ServiceCache.close()` on a cache that was never started is a no-op, as it is for
  `ServiceProvider`. Before, it threw `EtcdRecipeRuntimeException`.
- A `PathChildrenCache`'s own start worker is a daemon thread, so an unclosed primed cache
  no longer keeps the JVM from exiting.

### Fixed (`StickyStrategy` under concurrency)

- Concurrent `StickyStrategy` selections agree on one instance. When several callers found
  no usable choice at once, each picked an instance and the last to store its pick won, so a
  caller could return an instance another had already replaced, and later callers flipped to
  a stale choice. A pick now replaces only the choice its caller saw, so one made meanwhile
  is kept.

### Fixed (`DistributedAtomicLong` recovery)

- A failed first-use initialization no longer breaks the instance for good. A transient
  failure of the create-if-absent transaction (during an etcd leader change, say) used
  to leave `get()` returning `-1`, a legitimate counter value, and every update throwing
  `IllegalStateException("Empty KeyValue list")`. The next call now tries again.
- An absent counter key, whether never created or deleted by another process through
  `DistributedAtomicLong.delete`, now reads as `default`, and the next update re-creates
  it from `default` inside its compare-and-set. Live instances used to break the same
  way after a delete.
- `close()` now ends an update's compare-and-set loop, which never checked it, and the
  loop's random backoff is capped at one second instead of widening without limit.
- The create-if-absent transaction now runs under the recipe's RPC budget.

### Fixed (`TransientKeyValue` lifecycle)

- `TransientKeyValue` no longer parks an executor thread for its whole life. `start()`
  publishes the key synchronously under its self-healing lease, which renews on internal
  threads. Instances sharing a single-thread or small executor used to hang in the
  constructor or `start()`, since the second instance's task never ran; with a larger pool,
  each instance silently held a thread. `userExecutor` is no longer used and remains for
  compatibility.
- A `start()` that fails can be retried. The retry used to rethrow the first attempt's
  error (or, with the recipe's own executor, `RejectedExecutionException`) while a new
  task published the key anyway, and `close()` then threw "start() not called", leaving the
  key published for the life of the process. `close()` on an instance that never started
  is now a no-op.

### Fixed (coroutine cancellation safety)

- A coroutine cancelled just as its blocking call *succeeded* no longer leaks what the
  call got. `withContext`, which `runInterruptible` is built on, discards a result that
  arrives after its caller was cancelled. That leaked:
  - a `withLock` hold, which deadlocked every contender until `close()`, since the
    releasing thread was gone;
  - a `withPermit` / `awaitAcquire` / `awaitTryAcquire` permit;
  - a `receive()` item from `DistributedQueue` / `DistributedPriorityQueue`, deleted and
    dropped;
  - an `awaitReceive()` claim, stranded until the instance closed.

  Each is now given back before the cancellation propagates. A lock or permit is
  released. A queue item is put back under its original key: a priority queue keeps its
  place, and a FIFO queue, ordered by commit revision, gets it at the tail. A work item
  returns to the queue without spending a delivery attempt.
- A blocking call cancelled mid-flight now always surfaces as `CancellationException`, with
  the original failure as its cause. Before, an interrupt re-wrapped in the checked
  `EtcdRecipeException` (`awaitRegisterService`) or replaced by an exception with no
  cause (a barrier's "Failed to set waitingPath") escaped as that error. The bridge now
  classifies by the caller's job state as well as by the cause chain.
- `interruptOnPermitLoss` no longer interrupts a shared `Dispatchers.IO` worker. The
  suspending semaphore acquires now run on their own short-lived thread, so the permit's
  recorded holder is never a pooled thread running someone else's coroutine.

### Fixed (coroutine flows and parity)

- A watch abandoned for good no longer leaves its flow suspended forever. `watchAsFlow`
  completes after its `Recovery(Failed)` element, `watchEventsAsFlow` fails with
  `EtcdRecipeRuntimeException`, and `leadershipAsFlow` completes after `WatchFailed`.
- `leadershipAsFlow` takes an `rpc` parameter. A failed re-read after a recovery now ends
  the flow with `WatchFailed` instead of being logged and leaving the flow silently stale.
- The suspending RPC engine records `EtcdMetrics.recordRpc`, as the blocking one does
  (cancellation counts as a failure). Coroutine users' `etcd.rpc` timers and retry
  counters were always zero. A suspended single-attempt call that times out now carries
  the `TimeoutException` as its cause.
- Cache flows: the docs no longer suggest `onStart` as a sign that a flow is subscribed.
  A flow registers its listener asynchronously, so the example now starts with
  `BUILD_INITIAL_CACHE` and reads `currentData` instead of waiting for `INITIALIZED`.

### Fixed (integrations)

- Ktor: a plugin-owned client closes on `ApplicationStopped` instead of
  `ApplicationStopping`. Ktor runs handlers in registration order, so every
  `ApplicationStopping` handler registered after `install(EtcdPlugin)` (typically the app's
  own teardown) used to get a closed client. Recipes closed there then couldn't revoke their
  leases, so registrations and leader or lock keys lingered until their TTL.
- `EtcdConnectionConfig` and the Spring starter's `EtcdProperties` no longer show the
  password in `toString()`.
- `EtcdTlsConfig` requires `clientCertPath` and `clientKeyPath` together. Before, setting
  only one silently connected without a client certificate; now it throws
  `IllegalArgumentException` (and fails a Spring app's startup).
- Micrometer: `bindCacheSize` and `bindServiceCacheSize` tag `etcd.cache.entries` with
  `recipe=PathChildrenCache` / `recipe=ServiceCache`. Before, binding one of each to a
  registry returned the first gauge for the second, which reported the first recipe's
  value. The docs now say to `registry.remove(gauge)` when a recipe closes, and the gauge
  examples no longer bind to recipes they immediately close.
- The Spring starter depends on `kotlin-reflect` directly. Spring binds the all-defaults
  `EtcdProperties` through it, and it used to arrive only by way of another library; had
  that changed, `etcd.recipes.*` would have silently stopped binding. The starter's tests
  now check the bound values and the Actuator-absent case.

## [0.12.0] - 2026-07-26

The largest release since the project began, in five themes:

- **Connection resilience, on by default.** Watchers survive fatal stream deaths and
  re-anchor after compaction (part 1), leases self-heal and leaders step down on lease
  loss (part 2), and blocking RPCs gain operation timeouts and bounded retries instead
  of parking forever (part 3).
- **New recipes.** A whole `lock/` package (`DistributedMutex`,
  `DistributedReadWriteLock`, `DistributedSemaphore`), `DistributedWorkQueue`
  (at-least-once delivery with dead-lettering and delayed delivery), `LeaderLatch` /
  `LeaderObserver`, `NodeCache<T>`, and a load-balancing `ServiceProvider`.
- **A coroutine API.** Suspending twins of every blocking entry point plus `Flow`
  versions of the watch and listener streams, in `io.etcd.recipes.coroutines`. The
  blocking API is unchanged and remains the Java-facing surface.
- **Typed values and framework packaging.** `EtcdCodec<T>` types the KV extensions and
  every recipe that carried a raw payload, and four new optional modules cover Jackson,
  Micrometer, Spring Boot, and Ktor.
- **Observability.** A dependency-free `EtcdMetrics` SPI with a Micrometer binding,
  push-based background-exception notification, health checks, and recipe identity in
  the SLF4J MDC.

Also fixes a lost-wakeup race shared by every waiter in the library (locks, barriers,
and queues), and renames the published core artifact — see *Changed (breaking)* below.

**Breaking:** the core Maven coordinate is now `com.pambrose:etcd-recipes-core`.

### Changed (breaking: Maven coordinates)

- The core Gradle module (and its directory) was renamed `etcd-recipes` →
  **`etcd-recipes-core`**, so it reads as a sibling of the `-micrometer` / `-jackson` /
  `-spring-boot-starter` / `-ktor` modules rather than the ambiguous bare name that also
  names the repo and the shared module prefix. Because the artifactId derives from the
  module name, the published coordinate becomes `com.pambrose:etcd-recipes-core` —
  update your dependency declaration. **No package, class, or method name changed**:
  everything stays under `io.etcd.recipes.*`, so the upgrade is a one-line build-file
  edit. The repo, the sibling module names, and the Dokka footer keep the bare
  `etcd-recipes` prefix. (#82)

### Added (documentation site)

- A 31-page documentation site under `website/`, built with
  [Zensical](https://zensical.org) and published to
  <https://pambrose.github.io/etcd-recipes/> by a new docs workflow: recipe guides with
  Kotlin/Java tabs, the resilience and observability material, a coroutines section, a
  Java-interop guide (`@JvmName` facades, the `@JvmOverloads` ladder, and where
  `kotlin.time.Duration`'s value-class mangling leaves an API unreachable from Java),
  and the integration pages.
- None of the 306 code examples is written into the Markdown. Each is a real source file
  in a Gradle test source set — in the module whose API it documents — embedded at build
  time via `pymdownx.snippets`, so `./gradlew compileTestKotlin compileTestJava`
  type-checks every example on the site against the actual API and a dangling snippet
  reference fails the docs build rather than rendering an empty block. The snippet files
  are plain uninvoked functions and contribute zero tests to the suite.
- `make site` (serve locally), `make docs-check` (compile the snippets, then build the
  site strictly — what CI runs), plus `clean-site` / `check-site` / `upgrade-site`.

### Changed (build and tooling)

- Kotlin `2.4.0` → `2.4.10`; Gradle wrapper `9.5.1` → `9.6.1`; common-utils `2.9.0` →
  `3.2.1`; plus logback, junit, kotest, mockk, detekt, kotlinter, kover, shadow, and
  maven-publish bumps. Testcontainers, Micrometer `1.17.0`, Jackson `2.22.1`, and Ktor
  `3.5.1` are pinned in the catalog.
- The satellite modules' inline dependency coordinates moved into the version catalog,
  so every version in the build now lives in `gradle/libs.versions.toml`.
- The Spring Boot starter targets **Spring Boot 4.1.x**. Spring Boot 4 extracts the
  health API out of `spring-boot-actuator` into a separate `spring-boot-health`
  artifact, so the health types now come from `org.springframework.boot.health.contributor`.
  The starter's own API shape is unchanged.
- Remaining `java.util.concurrent.atomic` usages across the library, examples, and tests
  were converted to the stdlib `kotlin.concurrent.atomics` the repo had already
  standardized on — behavior-preserving and API-neutral (on the JVM they compile to the
  same primitives). (#81)
- `listOf(...)` / `mutableListOf<T>()` factory calls migrated to Kotlin collection-literal
  syntax; empty read-only lists keep `emptyList()` for the zero-allocation singleton.
- `./etcd-start.sh` (renamed from `etcd.sh`) and a new `./etcd-stop.sh` that stops the
  local etcd gracefully — SIGTERM, then SIGKILL after a 10s grace period — with
  `make etcd-start` / `make etcd-stop` targets. Plus `make all-tests` to run the local,
  Testcontainers, and multi-container variants in sequence.
- The test suite fails fast with a clear message when local etcd is unreachable instead
  of hanging (#49), Testcontainers' Ryuk reaper is enabled to silence prune-conflict
  warnings (#48), and the CI timeout moved 30 → 45 minutes to accommodate the docs
  compile. (#50)

### Added (cache: typed NodeCache + codec layer)

- **`NodeCache<T>`** — a watch-backed cache of a **single** etcd key, the counterpart to
  `PathChildrenCache` (which caches a prefix) for the "keep one config value hot, notify on
  change" case. `start()` snapshots the key and anchors the watch at the snapshot revision + 1
  (no establishment-race gap); `current` / `currentBytes` read the live value; a
  `NodeCacheListener` is notified of CREATED / UPDATED / DELETED; compaction of the watched
  revision re-syncs transparently. Plus a `withNodeCache { }` DSL and coroutine
  `eventsAsFlow()` / `recoveryEventsAsFlow()`.
- **`EtcdCodec<T>`** — a small pluggable-serialization SPI (`encode`/`decode`) with built-ins
  `ByteSequenceCodec`, `StringCodec`, and `KotlinxJsonCodec` (reified `jsonCodec<T>()`).
  `NodeCache<T>` decodes its payload through it; the other recipes gain typed variants in a
  later pass.

### Added (typed KV extensions + Jackson codec)

- **Typed KV extensions** — `Client.putValue(key, value, codec)` and `Client.getValue(key, codec)`
  encode / decode through any `EtcdCodec<T>`, removing the hand-marshalling that the raw
  `ByteSequence` overloads leave to the caller. Purely additive — the existing overloads are
  unchanged.
- **`etcd-recipes-jackson`** — a new optional module providing a Jackson-backed `JacksonCodec<T>`
  (`Class` / `TypeReference` constructors for Java callers, plus a reified `jacksonCodec<T>()` for
  Kotlin) for projects that prefer Jackson to kotlinx-serialization. Published as its own Maven
  Central artifact.

### Added (adoption packaging: Spring Boot starter, Ktor plugin, connection config)

- **`EtcdConnectionConfig`** + `connectToEtcd(config)` — declarative auth (user/password), key
  `namespace`, TLS (CA / client certs), and timeouts mapped onto the jetcd builder, so the common
  options no longer require the `initReceiver` escape hatch. Plus `Client.ping()` (a bare-client
  reachability probe) and an `EtcdRecipes` factory (path-scoped recipes over a shared client).
- **`etcd-recipes-spring-boot-starter`** — auto-configures a `Client` bean (graceful shutdown via
  `destroyMethod = "close"`) and an `EtcdRecipes` bean from `etcd.recipes.*` properties, plus an
  optional Actuator `HealthIndicator`. `@ConditionalOnMissingBean` lets an app override any bean.
  Spring Boot 4.1.x.
- **`etcd-recipes-ktor`** — a Ktor `Application` plugin that connects from config (or an injected
  client) and closes a plugin-owned client on `ApplicationStopping`; exposes `Application.etcdClient`
  / `Application.etcdRecipes`. Ktor 3.5.x.
- Both new modules are published as their own Maven Central artifacts.

### Added (typed recipe variants)

- Typed wrappers for the remaining raw-payload recipes, each marshalling through an `EtcdCodec<T>`
  so callers stop hand-encoding `ByteSequence`/`String`:
  - **`TypedDistributedQueue<T>`** / **`TypedDistributedPriorityQueue<T>`** — `enqueue(T)` /
    `dequeue(): T` / typed `poll`, over the existing queues.
  - **`TypedPathChildrenCache<T>`** — typed `currentData` / `getCurrentData` (`TypedChildData<T>`)
    and decoded `TypedPathChildrenCacheEvent`s via `TypedPathChildrenCacheListener` (plus a
    coroutine `eventsAsFlow()`).
  - **`TypedTransientKeyValue<T>`** — publishes a value encoded once through a codec.
  - **`ServiceInstance`** typed payload — additive `payload<T>(codec)` / `setPayload` extensions and
    a typed `serviceInstance(name, payload, codec)` builder, keeping the `jsonPayload` JSON string as
    the unchanged wire format.
- Each typed recipe is a composition wrapper exposing the underlying recipe as `untyped`, so the full
  `EtcdConnector` API stays reachable; no existing class changed. Codecs for the `String`-valued
  surfaces (`ServiceInstance`, `TransientKeyValue`) must emit UTF-8 text.

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

### Added (observability: structured logging)

- Recipe background-thread logging now carries an `etcd.recipe` MDC key (the recipe's identity,
  e.g. `DistributedMutex[/lock/x]`), so logs emitted on healer / watch-dispatcher /
  election-worker threads can be correlated to the recipe that produced them. Applied across
  the recipes' async runnables and their lease / lock-loss / watch-recovery handlers via a new
  protected `EtcdConnector.withRecipeLoggingContext { }`; `EtcdConnector.RECIPE_MDC_KEY` exposes
  the key name.

### Added (observability: live-state gauges)

- Micrometer gauges bound to a specific recipe instance (via the new
  `etcd-recipes-micrometer` binders): `MeterRegistry.bindQueueDepth`, `bindCacheSize` /
  `bindServiceCacheSize`, `bindAvailablePermits`, and `bindLeadership` register
  `etcd.queue.depth`, `etcd.cache.entries`, `etcd.semaphore.available`, and
  `etcd.election.leader`. Cache size and leadership are in-memory reads; queue depth and
  available permits poll a range-count RPC on **each scrape** (documented on the binders).
  Adds a public `AbstractQueue.size` accessor for the queue-depth gauge.

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
