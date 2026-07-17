# etcd-recipes

Distributed coordination primitives for [etcd](https://etcd.io) v3 on the JVM — locks,
leader election, barriers, queues, caches, counters and service discovery, for **Kotlin
and Java**.

It is, in spirit, what [Curator](https://curator.apache.org) is for
[ZooKeeper](https://zookeeper.apache.org): etcd gives you a consistent key/value store
with leases, watches and atomic transactions; etcd-recipes turns those primitives into
the coordination patterns you actually reach for.

```kotlin
connectToEtcd(listOf("http://localhost:2379")).use { client ->
  DistributedMutex(client, "/locks/orders").use { mutex ->
    mutex.withLock {
      // Exactly one process in the cluster is here at a time.
    }
  }
}
```

[Get started](getting-started/index.md){ .md-button .md-button--primary }
[Browse the recipes](recipes/index.md){ .md-button }

## What's in the box

<div class="grid cards" markdown>

- :material-lock: **[Locks](recipes/locks.md)** — `DistributedMutex`,
  `DistributedReadWriteLock`, `DistributedSemaphore`

- :material-crown: **[Leader election](recipes/election.md)** — `LeaderSelector`,
  `LeaderLatch`, `LeaderObserver`

- :material-gate: **[Barriers](recipes/barriers.md)** — simple, counted, and double barriers

- :material-tray-full: **[Queues](recipes/queues.md)** — FIFO, priority, and an
  at-least-once work queue with dead letters

- :material-database-sync: **[Caches](recipes/caches.md)** — `NodeCache<T>` for one key,
  `PathChildrenCache` for a prefix

- :material-counter: **[Counters](recipes/counter.md)** — `DistributedAtomicLong`

- :material-lan-connect: **[Service discovery](recipes/discovery.md)** — registry, cache,
  and a load-balancing provider

- :material-sync: **[Coroutines](coroutines/index.md)** — suspending twins and `Flow`
  event surfaces

</div>

## Why it might suit you

**It is a thin layer, not a wrapper.** The recipes are built on Kotlin extensions over
[jetcd](https://github.com/etcd-io/jetcd), and jetcd's `Client` is never hidden from you.
Use a recipe where it helps and drop to the [raw API](basics/index.md) where it doesn't —
they compose, because the recipes are written against the same extensions you get.

**Kotlin-first, genuinely Java-usable.** Every extension file carries a `@JvmName` facade,
constructors are `@JvmOverloads`, and listeners are SAM interfaces. Most pages here show
both languages side by side. See the [Java guide](java.md) for the specifics — and for the
handful of things (coroutines, `withLock { }`) that really are Kotlin-only.

**Failure is part of the API, not an afterthought.** A distributed lock can be *lost*
while you hold it. The recipes tell you when that happens rather than pretending it
cannot: lock-lost listeners, [connection state](resilience/connection-state.md),
`unlock()` returning `false`, and a deliberate policy on
[which leases self-heal and which must not](resilience/leases.md). That last page is the
one to read if you read only one.

**Observable by default.** An [`EtcdMetrics` SPI](observability.md) with no-op defaults,
a Micrometer binding, background-exception listeners, and SLF4J MDC context on worker
threads.

## Every example here is compiled

The code on this site is not typed into Markdown files. Each example is a real source
file in a Gradle test source set, embedded at build time. `./gradlew compileTestKotlin
compileTestJava` type-checks all of them against the actual API on every CI run, so an
example on this site cannot drift out of sync with the library. If you are curious how
that is wired up, it is described in
[the website README](https://github.com/pambrose/etcd-recipes/blob/master/website/README.md).

## Install

=== "Gradle (Kotlin)"

    ```kotlin
    dependencies {
      implementation("com.pambrose:etcd-recipes-core:0.12.0")
    }
    ```

=== "Gradle (Groovy)"

    ```groovy
    dependencies {
      implementation 'com.pambrose:etcd-recipes-core:0.12.0'
    }
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>com.pambrose</groupId>
      <artifactId>etcd-recipes-core</artifactId>
      <version>0.12.0</version>
    </dependency>
    ```

Optional modules — [Spring Boot](integrations/spring-boot.md), [Ktor](integrations/ktor.md),
[Jackson](integrations/jackson.md) and Micrometer — are listed in
[Integrations](integrations/index.md).

## Compatibility

| | |
| --- | --- |
| etcd | v3 |
| Java | 17+ |
| Built on | [jetcd](https://github.com/etcd-io/jetcd) |
| License | [Apache 2.0](https://github.com/pambrose/etcd-recipes/blob/master/License.txt) |
