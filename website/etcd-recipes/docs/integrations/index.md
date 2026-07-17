# Integrations

The library is deliberately small at its centre. `etcd-recipes-core` has the recipes and
nothing else; everything that would drag in a framework lives in its own artifact, so you
pull in Jackson only if you want Jackson, and Micrometer only if you want Micrometer.

## Artifacts

All published to Maven Central under the group `com.pambrose`, at version **0.12.0**.

| Artifact | What it gives you |
| --- | --- |
| `etcd-recipes-core` | The library: locks, elections, barriers, queues, caches, counters, service discovery, and the jetcd extension layer. Everything else depends on this. |
| `etcd-recipes-jackson` | `JacksonCodec<T>` — a Jackson-backed `EtcdCodec` for Java callers and anyone who would rather not use `kotlinx-serialization`. See [Jackson](jackson.md). |
| `etcd-recipes-micrometer` | `MicrometerEtcdMetrics` (the push metrics backend) and the `EtcdGauges` binders. See [below](#micrometer). |
| `etcd-recipes-spring-boot-starter` | Auto-configures a `Client`, an `EtcdRecipes` factory, and an Actuator health indicator from `application.yml`. See [Spring Boot](spring-boot.md). |
| `etcd-recipes-ktor` | An application plugin that owns an etcd connection for the server's lifetime. See [Ktor](ktor.md). |

=== "Gradle (Kotlin DSL)"

    ```kotlin
    dependencies {
      implementation("com.pambrose:etcd-recipes-core:0.12.0")

      // Optional, pick what you need:
      implementation("com.pambrose:etcd-recipes-jackson:0.12.0")
      implementation("com.pambrose:etcd-recipes-micrometer:0.12.0")
      implementation("com.pambrose:etcd-recipes-spring-boot-starter:0.12.0")
      implementation("com.pambrose:etcd-recipes-ktor:0.12.0")
    }
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>com.pambrose</groupId>
      <artifactId>etcd-recipes-core</artifactId>
      <version>0.12.0</version>
    </dependency>
    <!-- Optional; each brings etcd-recipes-core transitively. -->
    <dependency>
      <groupId>com.pambrose</groupId>
      <artifactId>etcd-recipes-jackson</artifactId>
      <version>0.12.0</version>
    </dependency>
    ```

Each satellite depends on core, so naming a satellite is enough — you do not need to list
core as well.

## `EtcdConnectionConfig`

The framework modules exist because of one shared problem: a framework needs to build the
client for you, from configuration, without a Kotlin lambda in the middle.
`EtcdConnectionConfig` is the declarative answer, and both the starter and the plugin bind
their own config onto it:

```kotlin
data class EtcdConnectionConfig(
  val endpoints: List<String>,
  val user: String? = null,
  val password: String? = null,
  val namespace: String? = null,
  val connectTimeout: Duration = Duration.ofSeconds(5),
  val retryMaxDuration: Duration = Duration.ofSeconds(30),
  val tls: EtcdTlsConfig? = null,
)

data class EtcdTlsConfig(
  val caCertPath: String? = null,
  val clientCertPath: String? = null,
  val clientKeyPath: String? = null,
)
```

Pass it to `connectToEtcd(config)`. In `EtcdTlsConfig`, `caCertPath` sets the trust manager
(server verification) and `clientCertPath` + `clientKeyPath` together enable mutual TLS.

!!! warning "These are `java.time.Duration`, not `kotlin.time.Duration`"

    `connectTimeout` and `retryMaxDuration` map straight onto jetcd's `ClientBuilder`,
    which is a Java API — so they are `java.time.Duration` and want
    `Duration.ofSeconds(5)`, not `5.seconds`. This is the one place in the library where
    that is true; the recipes themselves take `kotlin.time.Duration`. If you have a
    `kotlin.time.Duration` in hand, `.toJavaDuration()` converts it.

[Connecting](../getting-started/connecting.md) covers connections properly — endpoints,
auth, namespacing, TLS, and the `initReceiver` escape hatch for raw jetcd builder options.

## `EtcdRecipes`

`EtcdRecipes` is a thin factory: wire the connection once, then ask it for path-scoped
recipes bound to that client (and to a shared `ResilienceConfig`). Both framework modules
contribute one, but it works standalone — `EtcdRecipes(client)` is the whole setup.

```kotlin
class EtcdRecipes(client: Client, resilience: ResilienceConfig = ResilienceConfig.DEFAULT) {
  fun mutex(lockPath: String): DistributedMutex
  fun readWriteLock(lockPath: String): DistributedReadWriteLock
  fun semaphore(semaphorePath: String, permits: Int): DistributedSemaphore
  fun distributedQueue(queuePath: String): DistributedQueue
  fun distributedPriorityQueue(queuePath: String, minimumWaitTime: Duration = 0.milliseconds): DistributedPriorityQueue
  fun leaderLatch(electionPath: String): LeaderLatch
  fun pathChildrenCache(cachePath: String): PathChildrenCache
  fun <T> nodeCache(key: String, codec: EtcdCodec<T>): NodeCache<T>
  fun serviceDiscovery(servicePath: String): ServiceDiscovery
  fun distributedAtomicLong(counterPath: String): DistributedAtomicLong
}
```

Every method just constructs the recipe. **The factory does not own what it builds** — it
does not start it, does not close it, and does not track it. The `start()`/`close()`
lifecycle stays yours, exactly as if you had called the constructor.

!!! note "The factory is a convenience, not the API"

    It covers the recipes whose construction is `(client, path)` and little else. There is
    deliberately no `workQueue`, `leaderSelector`, `barrier`, or `leaderObserver` factory
    method: those take listeners, callbacks, or participant counts that a
    one-string-argument factory cannot express without becoming a worse constructor.
    Construct them directly — nothing about `EtcdRecipes` is privileged, and mixing the
    two styles in one application is fine.

## Micrometer

`etcd-recipes-micrometer` bridges the library's metrics SPI to a Micrometer
`MeterRegistry`. There are two halves, and they work differently.

### Push: `MicrometerEtcdMetrics`

`MicrometerEtcdMetrics(registry)` implements core's `EtcdMetrics` interface. Install it on
a `ResilienceConfig` and every RPC, watch recovery, lease keep-alive, lock wait, election
transition, queue operation, and cache sync in that config's blast radius reports itself:

```kotlin
--8<-- "kotlin/website/micrometer/MicrometerSnippets.kt:install"
```

`withMetrics()` returns a copy — `ResilienceConfig` is an immutable data class — so build
it once and hand it to everything. The `EtcdRecipes` factory is a good place to put it:

```kotlin
--8<-- "kotlin/website/micrometer/MicrometerSnippets.kt:factory"
```

### Pull: the `EtcdGauges` binders

Gauges are the other shape. A push meter fires when something happens; a gauge is polled
on every scrape, so it needs a live instance to poll. Bind one to a recipe you already
hold:

| Binder | Meter | Source |
| --- | --- | --- |
| `bindQueueDepth(queue, tags)` | `etcd.queue.depth` | `AbstractQueue.size` |
| `bindCacheSize(cache, tags)` | `etcd.cache.entries` | `PathChildrenCache.currentData.size` |
| `bindServiceCacheSize(cache, tags)` | `etcd.cache.entries` | `ServiceCache.instances.size` |
| `bindAvailablePermits(semaphore, tags)` | `etcd.semaphore.available` | `DistributedSemaphore.availablePermits()` |
| `bindLeadership(latch, tags)` | `etcd.election.leader` | `LeaderLatch.hasLeadership` (1.0 / 0.0) |

```kotlin
--8<-- "kotlin/website/micrometer/MicrometerSnippets.kt:gauges"
```

Micrometer holds only a weak reference to the bound instance, so a gauge never keeps a
recipe alive. Binding several instances of the same gauge to one registry needs
distinguishing `tags`, or they will collide.

!!! warning "Two of these gauges hit etcd on every scrape"

    `bindQueueDepth` and `bindAvailablePermits` poll accessors (`AbstractQueue.size`,
    `availablePermits()`) that issue a **range-count RPC**. At a 15-second scrape interval
    against a handful of queues that is nothing; across hundreds of bound instances, or at
    a 1-second interval, it is real load on the cluster. The other three are in-memory
    reads and cost nothing.

    ```kotlin
    --8<-- "kotlin/website/micrometer/MicrometerSnippets.kt:polling-gauge"
    ```

### Kotlin-only

The binders are Kotlin extension functions on `MeterRegistry` with default arguments and
no `@JvmOverloads`, so they are awkward from Java (`EtcdGaugesKt.bindQueueDepth(registry,
queue, Tags.empty())`). `MicrometerEtcdMetrics` itself is an ordinary class and works fine
from Java.

[Observability](../observability.md) has the full meter catalog — every name, tag, and
what it means.
