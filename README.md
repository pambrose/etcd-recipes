# etcd Recipes

[![Maven Central](https://img.shields.io/maven-central/v/com.pambrose/etcd-recipes.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.pambrose/etcd-recipes)
[![CI](https://github.com/pambrose/etcd-recipes/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/pambrose/etcd-recipes/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/e185b9c637b040bab55bdecf38b0de76)](https://www.codacy.com/manual/pambrose/etcd-recipes?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=pambrose/etcd-recipes&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/pambrose/etcd-recipes/branch/master/graph/badge.svg)](https://codecov.io/gh/pambrose/etcd-recipes)
[![Known Vulnerabilities](https://snyk.io/test/github/pambrose/etcd-recipes/badge.svg)](https://snyk.io/test/github/pambrose/etcd-recipes)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/%20language-Java-red.svg)](https://kotlinlang.org/)

[etcd-recipes](https://github.com/pambrose/etcd-recipes) is a Kotlin/Java/JVM client library
for [etcd](https://etcd.io) v3, a distributed, reliable key-value store. It provides higher-level
distributed coordination primitives layered on top of the raw key/value API — similar in spirit to
what [Curator](https://curator.apache.org) provides for [ZooKeeper](https://zookeeper.apache.org).

## Recipes

| Package | Recipes |
|---|---|
| `io.etcd.recipes.barrier` | `DistributedBarrier`, `DistributedBarrierWithCount`, `DistributedDoubleBarrier` |
| `io.etcd.recipes.cache` | `PathChildrenCache` (key-prefix cache), `TypedPathChildrenCache<T>`, `NodeCache<T>` (typed single-key cache) |
| `io.etcd.recipes.counter` | `DistributedAtomicLong` |
| `io.etcd.recipes.discovery` | `ServiceDiscovery`, `ServiceCache`, `ServiceInstance` (typed `payload<T>()`), `ServiceProvider`, `ProviderStrategy` |
| `io.etcd.recipes.election` | `LeaderSelector`, `LeaderLatch`, `LeaderObserver`, `LeaderSelectorListener`, `Participant` |
| `io.etcd.recipes.keyvalue` | `TransientKeyValue` (lease-backed key/value), `TypedTransientKeyValue<T>` |
| `io.etcd.recipes.lock` | `DistributedMutex`, `DistributedReadWriteLock`, `DistributedSemaphore` |
| `io.etcd.recipes.queue` | `DistributedQueue`, `DistributedPriorityQueue`, `DistributedWorkQueue` (at-least-once), `TypedDistributedQueue<T>` / `TypedDistributedPriorityQueue<T>` |
| `io.etcd.recipes.common` | Kotlin extensions over jetcd `Client`, `KV`, `Lease`, `Watch`, `Txn`; `EtcdCodec<T>` typed values |
| `io.etcd.recipes.coroutines` | Suspending twins of the `common` extensions, `Flow`-based watches |

## Usage

Connect to a cluster and read/write keys with the extension API:

```kotlin
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.putValue
import kotlin.time.Duration.Companion.seconds

val urls = listOf("http://localhost:2379")

connectToEtcd(urls) { client ->
    client.putValue("test_key", "test_value")
    Thread.sleep(5.seconds.inWholeMilliseconds)
    client.delete("test_key")
}
```

Read and write typed values through an `EtcdCodec<T>` instead of hand-marshalling bytes:

```kotlin
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.common.putValue
import kotlinx.serialization.Serializable

@Serializable
data class Config(val name: String, val count: Int)

connectToEtcd(urls) { client ->
    val codec = jsonCodec<Config>()
    client.putValue("/config/app", Config("svc", 3), codec)
    val config: Config? = client.getValue("/config/app", codec)
}
```

Built-in codecs are `StringCodec`, `ByteSequenceCodec`, and `jsonCodec<T>()`
(kotlinx-serialization). Java projects can add the optional `etcd-recipes-jackson`
module for a `JacksonCodec<T>`. The same codecs type the recipes: `NodeCache<T>`,
`TypedPathChildrenCache<T>`, `TypedDistributedQueue<T>` /
`TypedDistributedPriorityQueue<T>`, `TypedTransientKeyValue<T>`, and `ServiceInstance`'s
`payload<T>(codec)` / typed `serviceInstance(name, payload, codec)` builder.

Run a single-leader election across a cluster of processes:

```kotlin
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.election.LeaderSelector

connectToEtcd(urls) { client ->
    LeaderSelector(
        client,
        electionPath = "/election/my-service",
        takeLeadershipBlock = { selector ->
            // Invoked on the elected leader; return to release leadership.
        },
        clientId = "node-1",
    ).use { selector ->
        selector.start()
        selector.waitOnLeadershipComplete()
    }
}
```

`LeaderSelector` is callback-scoped — leadership lives inside `takeLeadershipBlock`.
For the "acquire leadership and **hold it until shutdown**" shape, use `LeaderLatch`:

```kotlin
import io.etcd.recipes.election.LeaderLatch

connectToEtcd(urls) { client ->
    LeaderLatch(client, "/election/my-service", clientId = "node-1").use { latch ->
        latch.start()
        latch.await()               // block until this node holds leadership
        // hold leadership and do leader-only work; hasLeadership stays true…
    }                               // …until close() releases candidacy
}
```

`LeaderLatch` composes `LeaderSelector`, so latches and selectors interoperate in
one election; query `hasLeadership`, block on `await()` / `await(timeout)`, or
register a `LeaderLatchListener` (`isLeader`/`notLeader`). If the leadership lease is
lost it fires `notLeader` and re-contests. To *observe* an election without being a
candidate, use `LeaderObserver` (blocking, with a `currentLeader` snapshot and a
`LeaderListener`) or the coroutine `Client.leadershipAsFlow(path)`.

See the `etcd-recipes-examples/` module for runnable
[Java](https://github.com/pambrose/etcd-recipes/tree/master/etcd-recipes-examples/src/main/java/io/etcd/recipes/examples)
and [Kotlin](https://github.com/pambrose/etcd-recipes/tree/master/etcd-recipes-examples/src/main/kotlin/io/etcd/recipes/examples)
demos of every recipe.

## Framework integration

`connectToEtcd` also takes a declarative `EtcdConnectionConfig` (endpoints, auth, key `namespace`,
TLS, timeouts) instead of the `initReceiver` lambda:

```kotlin
connectToEtcd(EtcdConnectionConfig(endpoints = urls, namespace = "/myapp/", user = "root", password = "…"))
```

Two optional modules wire that into the common server stacks.

**Spring Boot** (`etcd-recipes-spring-boot-starter`) — configure `application.yml` and the client is
auto-configured (and closed gracefully on shutdown), alongside an `EtcdRecipes` factory bean and an
optional Actuator `etcd` health indicator:

```yaml
etcd:
  recipes:
    endpoints: [ "http://localhost:2379" ]
    namespace: /myapp/
```

**Ktor** (`etcd-recipes-ktor`) — install the plugin; `application.etcdClient` and
`application.etcdRecipes` become available, and a plugin-owned client closes on `ApplicationStopping`:

```kotlin
install(EtcdPlugin) {
    endpoints = listOf("http://localhost:2379")
    namespace = "/myapp/"
}
```

## Coroutines API

Every blocking operation stays as-is (Java parity); a Kotlin-first async surface
lives alongside it in `io.etcd.recipes.coroutines`. Suspending twins of the
`common` extensions take the `await` prefix (`awaitPutValue`, `awaitGetValue`,
`awaitTransaction`, ...) and share the same retry policies and operation
timeouts — backing off with `delay` instead of parking a thread, and treating
coroutine cancellation as authoritative (the in-flight RPC future is cancelled,
never retried).

Watches become `Flow`s:

```kotlin
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.coroutines.WatchFlowEvent
import io.etcd.recipes.coroutines.watchAsFlow

connectToEtcd(urls).use { client ->
    client.watchAsFlow("/config", watchOption { isPrefix(true) }).collect { element ->
        when (element) {
            is WatchFlowEvent.Response -> element.response.events.forEach { /* react */ }
            is WatchFlowEvent.Recovery -> { /* Suspended / Resubscribed / Resynced / Failed */ }
        }
    }
}
```

`watchAsFlow` rides the same resilient watcher as the blocking API: fatal stream
deaths recover automatically, and compaction resyncs surface in-band as
`WatchFlowEvent.Recovery` so collectors with derived state can rebuild. The
default unlimited buffer means a slow collector can never stall the watch
dispatcher; cancelling the collector closes the watcher. Use
`watchEventsAsFlow` for a flattened `Flow<WatchEvent>` when no derived state is
kept.

Every recipe's blocking entry points have suspending twins that run the
blocking call on `Dispatchers.IO`, so a coroutine can wait on a queue, barrier,
lock, or counter without dedicating a thread — and cancelling the coroutine
aborts the wait, cleaning up any queued entry or lease:

| Recipe | Blocking | Suspending |
|---|---|---|
| `DistributedQueue` | `dequeue()` / `poll(t)` / `enqueue(v)` | `receive()` / `receive(t)` / `awaitEnqueue(v)` |
| `DistributedWorkQueue` | `receive()` / `WorkItem.ack()` | `awaitReceive()` / `WorkItem.awaitAck()` |
| `DistributedBarrier` | `waitOnBarrier()` | `await()` / `await(t)` |
| `DistributedBarrierWithCount` | `waitOnBarrier()` | `await()` / `await(t)` |
| `DistributedMutex` / RW lock | `lock { }` (thread-owned) | `withLock { }` (scoped) |
| `DistributedSemaphore` | `acquire()` / `withPermit { }` | `awaitAcquire()` / `withPermit { }` |
| `DistributedAtomicLong` | `increment()` / `add(n)` | `awaitIncrement()` / `awaitAdd(n)` |
| `LeaderSelector` / `PathChildrenCache` | `start()` / `waitOn…()` | `awaitStart()` / `await…()` |

The thread-owned locks (`DistributedMutex`, `DistributedReadWriteLock`) expose
only the scoped `withLock { }` — ownership is pinned to the acquiring thread, so
acquisition and release are confined to one thread per call while the body runs
in your coroutine. The instance-held `DistributedSemaphore` also offers the
split `awaitAcquire()` / `awaitRelease()`.

Recipe event streams — previously callback listeners — are also exposed as
`Flow`s. Collecting a flow registers the underlying listener; cancelling the
collector unregisters it, and collection never starts or closes the recipe:

| Surface | Flow |
|---|---|
| `Client` watches | `watchAsFlow(...)` / `watchEventsAsFlow(...)` |
| `PathChildrenCache` child events | `eventsAsFlow()` / `recoveryEventsAsFlow()` |
| `NodeCache` key changes | `eventsAsFlow()` / `recoveryEventsAsFlow()` |
| `ServiceCache` changes | `eventsAsFlow()` / `recoveryEventsAsFlow()` |
| Any recipe's connection state | `connectionStateAsFlow()` |
| Lease events (`TransientKeyValue`, `DistributedWorkQueue`, `ServiceRegistry`) | `leaseEventsAsFlow()` |
| Leadership at an election path | `Client.leadershipAsFlow(path)` |
| Lock / permit loss | `EtcdLock.lockLostAsFlow()` / `DistributedSemaphore.permitLostAsFlow()` |

`connectionStateAsFlow()` emits the current state first (conflated); the others
are unbuffered by default, so the recipe's dispatcher is never stalled by a slow
collector. `leadershipAsFlow` is an observer of who holds leadership — to run for
election, use `LeaderSelector` with the suspending `awaitStart()` /
`awaitLeadershipComplete()`.

## Distributed locks

`DistributedMutex` is a reentrant distributed lock on etcd's **native lock
service** — waiters queue server-side (FIFO by revision), and jetcd applies
`requireLeader`, so a waiter never blocks silently against a partitioned server:

```kotlin
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.withLock

DistributedMutex(client, "/locks/inventory").use { mutex ->
    mutex.withLock {
        // exactly one holder across all processes and threads
    }

    if (mutex.tryLock(5.seconds)) {          // bounded acquisition; nothing leaks on timeout
        try { /* ... */ } finally { mutex.unlock() }
    }
}
```

Holds are **per-thread** (Curator parity): a second thread on the same instance
queues in etcd like a second process, and `unlock()` from a non-owner throws.
Each acquisition owns a lease that is renewed through the wait and the hold. If
a holder is partitioned longer than the TTL, etcd grants the lock to the next
waiter and the dispossessed holder observes it **cooperatively**:
`isHeldByCurrentThread` turns false, its `LockLostListener` fires, connection
state reports `LOST`, and `unlock()` returns false (interruption is opt-in via
`interruptOnLockLoss`). The lock is deliberately never auto-reclaimed.

`DistributedReadWriteLock` adds shared/exclusive semantics with the same lock
surface (`rw.readLock` / `rw.writeLock`, both `EtcdLock`s): readers share,
writers exclude, and grants are **fair** — FIFO by arrival revision, so a queued
writer is never starved by later readers. Write→read downgrade is supported;
read→write upgrade throws (it would self-deadlock).

`DistributedSemaphore` caps concurrency across processes with a counting
semaphore (`acquire()` / `tryAcquire(timeout)` / `release()` /
`withPermit { }`). The permit count is stored once at the semaphore path and
validated by every instance, grants are FIFO, and capacity is never exceeded.
Holds are instance-level, Java-`Semaphore`-style: any thread may release
(releases are LIFO among the instance's holds), and a permit whose lease
expires is lost cooperatively — a `PermitLostListener` fires and the matching
`release()` returns false.

## Load-balancing service provider

`ServiceProvider` hands out instances of a registered service with a pluggable
[`ProviderStrategy`](etcd-recipes/src/main/kotlin/io/etcd/recipes/discovery/ProviderStrategy.kt)
— `RandomStrategy` (the default), `RoundRobinStrategy`, or `StickyStrategy` (session
affinity) — the basis for client-side load balancing. Call `start()` to back reads
with a watch-updated `ServiceCache` (in-memory, current); without `start()` each read
does a direct etcd lookup (the original behavior). Mark a failing instance with
`noteError`: after an error threshold it is ejected from selection for a down window,
then automatically becomes eligible again.

```kotlin
import io.etcd.recipes.discovery.RoundRobinStrategy
import io.etcd.recipes.discovery.withServiceDiscovery

withServiceDiscovery(client, "/services/app") {
    withServiceProvider("worker", RoundRobinStrategy()) {
        start()                         // watch-backed, in-memory reads
        val instance = getInstance()    // round-robin pick
        // … on a failed request to `instance`:
        noteError(instance)             // eject it from rotation for a while
    }
}
```

Stateful strategies (`RoundRobinStrategy`, `StickyStrategy`) hold per-provider state —
use a fresh one per provider. The observer-only `LeaderObserver` /
`Client.leadershipAsFlow` are the election analogues.

## Reliable work queue

`DistributedQueue`'s take is at-most-once by construction: the item is deleted
before the consumer processes it, so a crash between the two loses the message.
`DistributedWorkQueue` is the at-least-once alternative — an item survives until
it is explicitly acknowledged:

```kotlin
import io.etcd.recipes.queue.DistributedWorkQueue
import io.etcd.recipes.queue.WorkQueueConfig

DistributedWorkQueue(client, "/queues/emails", WorkQueueConfig(maxDeliveries = 3)).use { queue ->
    queue.enqueue("send-invoice-42")

    val item = queue.receive()      // or receive(timeout) / tryReceive()
    try {
        process(item.value)
        item.ack()                  // false = the claim was lost; work may have been redone
    } catch (e: Exception) {
        item.requeue()              // early nack: back to its original position
    }
}
```

Received items are *claimed*, not deleted: the payload stays in etcd and only the
claim marker is bound to the consumer's lease. If the consumer crashes (or is
partitioned longer than `visibilityTimeoutSecs`), the marker expires and any
consumer's reclaim sweep returns the item to its original FIFO position — with
`attempt` incremented — or moves it to the dead-letter space once `maxDeliveries`
is exhausted (`deadLetters()` / `requeueDeadLetter(id)` / `purgeDeadLetter(id)`).
A live consumer renews its lease, so processing may take longer than the
visibility timeout — it bounds crash detection, not processing time.

Delivery can be deferred: `enqueue(value, delay)` keeps the item invisible until
it matures, then it flows through the normal claim/ack lifecycle in ready-time
order, without blocking immediate items. Maturity rides on client clocks —
producer/consumer skew shifts delivery by the skew.

For fire-and-forget delivery the plain queues also gained bounded consumption:
`tryDequeue()` (non-blocking), `poll(timeout)`, and atomic `enqueueAll(values)`.

## Connection resilience

Watchers created through this library survive fatal watch-stream deaths — **on by
default** as of 0.12.0. The division of labor:

| Failure | Handled by | How |
|---|---|---|
| Connection blip, etcd restart | jetcd | Internal stream resume with revision continuity (~500&nbsp;ms retry) |
| Compaction of the watched revision | etcd-recipes | Re-read state (recipe resync), re-anchor the watch at the fresh revision |
| Halt-error statuses, "no leader" | etcd-recipes | Re-subscribe from the last observed revision with configurable backoff |

Recipes that maintain derived state (`PathChildrenCache`, `ServiceCache`) reconcile
their maps during a compaction resync instead of going silently stale; parked waiters
(barriers, queue `dequeue()`, `LeaderSelector` re-election) re-probe their condition
after every recovery, and an abandoned recovery unparks them with an error rather
than hanging forever.

Recovery pacing is a [`RetryPolicy`](etcd-recipes/src/main/kotlin/io/etcd/recipes/common/RetryPolicy.kt)
(default: exponential backoff, 250&nbsp;ms → 15&nbsp;s, unbounded). Every recipe takes an
optional trailing `ResilienceConfig`:

```kotlin
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.WatchResilience

// Tune the backoff (or pass ResilienceConfig.DISABLED for pre-0.12 behavior):
val resilience = ResilienceConfig(watch = WatchResilience(RetryPolicy.exponentialBackoff(maxAttempts = 20)))
val cache = PathChildrenCache(client, cachePath, resilience = resilience)

// Observe recovery from application code:
cache.addRecoveryListener { event -> println("cache watch recovery: $event") }
```

Recovery events (`Suspended`, `Resubscribed`, `Resynced`, `Failed`) are exposed via
`addRecoveryListener` on the caches and via the low-level
`client.watcher(key, option, resilience, recoveryListener, resyncWith) { ... }`
extension. `Failed` (retry policy exhausted) is also recorded in the connector's
`exceptions` list. Watchers request etcd progress notifications by default so the
resume revision stays fresh on quiet keys; watch blocks see them as
`WatchResponse`s with an empty event list.

### Self-healing leases

Lease-holding recipes survive lease expiry (a partition longer than the TTL) — also
on by default. jetcd restarts a keep-alive stream after transient errors by itself;
what it cannot recover is an *expired* lease, whose bound keys etcd has deleted.
The `Client.selfHealingKeepAlive(ttl, resilience, listener) { lease -> ... }`
primitive re-grants the lease and re-runs the recipe's establish hook, and every
lease-holding recipe uses it:

| Recipe | On lease expiry |
|---|---|
| `TransientKeyValue` | Key is re-put under the healed lease |
| `ServiceRegistry` | Instance is re-registered (CAS; unique ids make this safe) |
| `DistributedBarrier` | Barrier re-arms for future waiters (waiters that saw the expiry DELETE already passed — that window is unavoidable; the `Expired` event is the signal) |
| `DistributedBarrierWithCount` | A parked waiter's registration heals so the barrier can still trip |
| `LeaderSelector` (participation) | Candidacy is re-advertised |
| `LeaderSelector` (leadership) | **Never healed — the leader steps down**: `isLeader` turns false, the `takeLeadership` block is released (interrupted if parked in its own code; disable via the `interruptOnLeaseLoss` constructor parameter), and `relinquishLeadership` runs. Another node may already lead; reclaiming would race it. |

Lease events (`Suspended`, `Expired`, `Restored`, `Failed`) are observable via
`addLeaseListener` on `TransientKeyValue` and `ServiceRegistry`, and are recorded
on the connector's `exceptions` list.

### Connection-state listeners

Every recipe derives a coarse connection state — `CONNECTED`, `SUSPENDED`,
`RECONNECTED`, `LOST` — passively from its own watch and lease streams:

```kotlin
selector.addConnectionStateListener { new, prev ->
    if (new == ConnectionState.LOST) log.warn("ownership may be gone")
}
```

`LOST` means a lease expired or recovery was abandoned — ownership may have been
lost during the outage (a leader, for example, has already stepped down by then).

### RPC timeouts and retries

Every blocking extension call (`putValue`, `getValue`, `deleteKey`, `leaseGrant`,
...) used to block on an unbounded `future.get()` — against an unreachable cluster
it parked forever. Each attempt is now bounded by a 30&nbsp;s operation timeout,
and retriable failures (`UNAVAILABLE`, `INTERNAL`, `DEADLINE_EXCEEDED`, or a
timeout) are retried under a bounded policy (4 attempts, 250&nbsp;ms apart) —
configurable per call or per recipe via `ResilienceConfig.rpc`:

```kotlin
// One-shot with a tight deadline for a latency-sensitive path:
val value = client.getValue("/config/flag", "default", RpcResilience(RetryPolicy.never, 2.seconds))
```

`transaction { }` is **never retried** regardless of policy — a failed commit is
ambiguous (it may have been applied), and CAS retry decisions belong to the
recipes' own loops. The timeout still applies.

`connectToEtcd` also applies recipe-tuned client defaults before your own builder
settings (which win): a 5&nbsp;s `connectTimeout` and a 30&nbsp;s
`retryMaxDuration` bound on jetcd's internal per-call retries. jetcd 0.8.6's own
defaults already enable `waitForReady` and gRPC keepalive (30&nbsp;s /
10&nbsp;s timeout).

## Compatibility

- Built on [jetcd](https://github.com/etcd-io/jetcd) and targets etcd v3.
- Requires Java 17+ at runtime (the published artifact is compiled against a JDK 17 toolchain).
- Written in Kotlin; fully usable from Java and any other JVM language.


## Download

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.pambrose:etcd-recipes:0.11.0")
}
```

If you use a version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
etcd-recipes = "0.11.0"

[libraries]
etcd-recipes = { module = "com.pambrose:etcd-recipes", version.ref = "etcd-recipes" }
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.pambrose:etcd-recipes:0.11.0'
}
```

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>com.pambrose</groupId>
        <artifactId>etcd-recipes</artifactId>
        <version>0.11.0</version>
    </dependency>
</dependencies>
```

## Building from source

JDK 17 is required (the build is configured with a Kotlin JVM toolchain of 17). The Gradle wrapper
is checked in, so no local Gradle install is needed.

```
./gradlew clean build -xtest      # build without running tests
./gradlew check                   # run all tests + Kover coverage
./gradlew lintKotlin              # kotlinter + detekt
```

A `Makefile` wraps the most common entry points (`make help` lists everything):

```
make build              # clean + build, skipping tests
make tests              # full test suite against a local etcd at localhost:2379
make tests-tc           # full test suite against an ephemeral Testcontainers etcd
make tests-container    # multi-container variant: each participant in its own container
make lint               # kotlinter + detekt
make coverage           # Kover HTML + XML reports
make kdocs              # Dokka HTML / Javadoc
make versions           # gradle dependencyUpdates
```

`make tests` and the examples expect a local etcd at `http://localhost:2379`. Start one with:

```
./etcd.sh
```

`make tests-tc` and `make tests-container` stand up etcd via Testcontainers and require Docker.

To run a single test class:

```
./gradlew :etcd-recipes:test --tests "io.etcd.recipes.barrier.DistributedBarrierTests"
```

### Test variants

Two complementary variants of the distributed-coordination tests live in the repo:

- **Thread-based** (`*Tests.kt` under each recipe directory) — N threads in a single JVM
  simulate distributed clients. Fast; runs by default.
- **Container-based** (`container/Container*Test.kt`) — each participant runs in its own
  container against a shared etcd container, exercising true cross-process coordination.
  Built from the `etcd-recipes-test-runners` submodule's shadow JAR. Gated by
  `-PuseTestcontainers`; run via `make tests-container`.

## Contributing

Issues and pull requests are welcome on [GitHub](https://github.com/pambrose/etcd-recipes). When
adding a new recipe, please include a runnable example under `etcd-recipes-examples/` and Kotest
tests under `etcd-recipes/src/test/kotlin/`.

## License

Released under the [Apache License, Version 2.0](License.txt).
