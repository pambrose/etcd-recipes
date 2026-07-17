# Getting started

## Requirements

| | |
| --- | --- |
| Java | 17 or newer |
| etcd | v3 |

## Add the dependency

The core library is all you need for every recipe on this site.

=== "Gradle (Kotlin)"

    ```kotlin
    repositories {
      mavenCentral()
    }

    dependencies {
      implementation("com.pambrose:etcd-recipes-core:0.12.0")
    }
    ```

=== "Gradle (Groovy)"

    ```groovy
    repositories {
      mavenCentral()
    }

    dependencies {
      implementation 'com.pambrose:etcd-recipes-core:0.12.0'
    }
    ```

=== "Version catalog"

    ```toml
    [versions]
    etcd-recipes = "0.12.0"

    [libraries]
    etcd-recipes-core = { module = "com.pambrose:etcd-recipes-core", version.ref = "etcd-recipes" }
    ```

=== "Maven"

    ```xml
    <dependencies>
      <dependency>
        <groupId>com.pambrose</groupId>
        <artifactId>etcd-recipes-core</artifactId>
        <version>0.12.0</version>
      </dependency>
    </dependencies>
    ```

The optional modules — Spring Boot, Ktor, Jackson, Micrometer — are listed with their
coordinates in [Integrations](../integrations/index.md).

## Run an etcd

You need an etcd to talk to. The quickest local one:

```bash
etcd --listen-client-urls=http://localhost:2379 \
     --advertise-client-urls=http://localhost:2379
```

Or with Docker:

```bash
docker run --rm -p 2379:2379 \
  quay.io/coreos/etcd:v3.5.17 \
  etcd --listen-client-urls=http://0.0.0.0:2379 \
       --advertise-client-urls=http://localhost:2379
```

!!! tip "Working in a clone of the repo?"

    `./etcd.sh` starts one with those flags, and `./etcd-stop.sh` shuts it down
    gracefully. The data directory `default.etcd/` is gitignored.

## Your first program

Connect, write a key, read it back:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/gettingstarted/ConnectSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/gettingstarted/ConnectSnippets.java:basic"
    ```

Two things in that snippet are worth naming now, because they hold everywhere:

- **You own the `Client`.** `connectToEtcd` hands you a jetcd `Client` and closing it is
  your job — hence `use` / try-with-resources. Recipes take a `Client`; they never close
  one for you.
- **Java goes through a facade.** The Kotlin extensions live in files annotated
  `@file:JvmName("ClientUtils")`, `@file:JvmName("KVUtils")` and so on, which is what makes
  `KVUtils.putValue(client, ...)` available. The [Java guide](../java.md) maps the lot.

## Now do something distributed

The point of the library is the recipes. Here is a mutex — one process in the whole
cluster inside the block at a time:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/MutexSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/MutexSnippets.java:basic"
    ```

Run it in two JVMs at once and watch them take turns.

## Where to go next

<div class="grid cards" markdown>

- :material-connection: **[Connecting](connecting.md)** — auth, TLS, namespaces, timeouts,
  health checks

- :material-cube-outline: **[Core concepts](concepts.md)** — the lifecycle every recipe
  shares, and how failures surface. **Read this one.**

- :material-book-open-variant: **[Recipes](../recipes/index.md)** — the catalog

- :material-coffee: **[Java guide](../java.md)** — if you are not writing Kotlin

</div>
