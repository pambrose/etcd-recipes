# etcd Recipes

[![Maven Central](https://img.shields.io/maven-central/v/com.pambrose.etcd-recipes/etcd-recipes.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.pambrose.etcd-recipes/etcd-recipes)
[![CI](https://github.com/pambrose/etcd-recipes/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/pambrose/etcd-recipes/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/e185b9c637b040bab55bdecf38b0de76)](https://www.codacy.com/manual/pambrose/etcd-recipes?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=pambrose/etcd-recipes&amp;utm_campaign=Badge_Grade)
[![codebeat badge](https://codebeat.co/badges/d61556d4-22e8-44c3-b8f8-db7613fae7fc)](https://codebeat.co/projects/github-com-pambrose-etcd-recipes-master)
[![codecov](https://codecov.io/gh/pambrose/etcd-recipes/branch/master/graph/badge.svg)](https://codecov.io/gh/pambrose/etcd-recipes)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=pambrose_etcd-recipes&metric=alert_status)](https://sonarcloud.io/dashboard?id=pambrose_etcd-recipes)
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
| `io.etcd.recipes.cache` | `PathChildrenCache` (key-prefix cache with PUT/UPDATE/DELETE listeners) |
| `io.etcd.recipes.counter` | `DistributedAtomicLong` |
| `io.etcd.recipes.discovery` | `ServiceDiscovery`, `ServiceCache`, `ServiceInstance`, `ServiceProvider` |
| `io.etcd.recipes.election` | `LeaderSelector`, `LeaderSelectorListener`, `Participant` |
| `io.etcd.recipes.keyvalue` | `TransientKeyValue` (lease-backed key/value) |
| `io.etcd.recipes.queue` | `DistributedQueue`, `DistributedPriorityQueue` |
| `io.etcd.recipes.common` | Kotlin extensions over jetcd `Client`, `KV`, `Lease`, `Watch`, `Txn` |

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

See the `etcd-recipes-examples/` module for runnable
[Java](https://github.com/pambrose/etcd-recipes/tree/master/etcd-recipes-examples/src/main/java/io/etcd/recipes/examples)
and [Kotlin](https://github.com/pambrose/etcd-recipes/tree/master/etcd-recipes-examples/src/main/kotlin/io/etcd/recipes/examples)
demos of every recipe.

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
    implementation("com.pambrose.etcd-recipes:etcd-recipes:0.10.0")
}
```

If you use a version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
etcd-recipes = "0.10.0"

[libraries]
etcd-recipes = { module = "com.pambrose.etcd-recipes:etcd-recipes", version.ref = "etcd-recipes" }
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.pambrose.etcd-recipes:etcd-recipes:0.10.0'
}
```

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>com.pambrose.etcd-recipes</groupId>
        <artifactId>etcd-recipes</artifactId>
        <version>0.10.0</version>
    </dependency>
</dependencies>
```

## Building from source

JDK 17 is required (the build is configured with a Kotlin JVM toolchain of 17). The Gradle wrapper
is checked in, so no local Gradle install is needed.

```
./gradlew clean build -xtest    # build without running tests
./gradlew check                 # run all tests + jacoco coverage
./gradlew lintKotlinMain lintKotlinTest
```

A `Makefile` wraps the most common entry points:

```
make build          # ./gradlew clean build -xtest
make tests          # ./gradlew check jacocoTestReport
make lint           # ./gradlew lintKotlinMain lintKotlinTest
make versioncheck   # ./gradlew dependencyUpdates --no-parallel
```

The integration tests and examples expect a local etcd at `http://localhost:2379`. Start one with:

```
./etcd.sh
```

To run a single test class:

```
./gradlew :etcd-recipes:test --tests "io.etcd.recipes.barrier.DistributedBarrierTests"
```

## Contributing

Issues and pull requests are welcome on [GitHub](https://github.com/pambrose/etcd-recipes). When
adding a new recipe, please include a runnable example under `etcd-recipes-examples/` and Kotest
tests under `etcd-recipes/src/test/kotlin/`.

## License

Released under the [Apache License, Version 2.0](License.txt).
