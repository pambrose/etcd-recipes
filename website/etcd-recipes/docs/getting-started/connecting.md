# Connecting

Everything in the library takes a jetcd `Client`. This page is about getting one.

## `connectToEtcd`

The `ClientUtils` facade (`common/ClientExtensions.kt`) offers four overloads: a URL list
or an `EtcdConnectionConfig`, each in a plain form and a scoped `block` form.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/gettingstarted/ConnectSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/gettingstarted/ConnectSnippets.java:basic"
    ```

The scoped form closes the client for you when the block returns — including on a throw:

```kotlin
--8<-- "kotlin/website/gettingstarted/ConnectSnippets.kt:scoped"
```

!!! warning "You own the client's lifetime"

    A recipe never closes the `Client` you hand it — closing a recipe closes the recipe,
    not your connection. That is deliberate: one client is normally shared by many
    recipes. The exception is the [Ktor plugin](../integrations/ktor.md), which closes a
    client it created itself but never one you injected.

Under the hood both forms apply `ClientBuilder.withRecipeDefaults()`, the settings the
recipes assume.

## `EtcdConnectionConfig`

For anything beyond a URL list — credentials, TLS, a namespace — use the config object:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/gettingstarted/ConnectSnippets.kt:config"
    ```

=== "Java"

    ```java
    --8<-- "java/website/gettingstarted/ConnectSnippets.java:config"
    ```

| Field | Default | Notes |
| --- | --- | --- |
| `endpoints` | — | required |
| `user` / `password` | `null` | etcd RBAC |
| `namespace` | `null` | transparent key prefix |
| `connectTimeout` | 5s | `java.time.Duration` |
| `retryMaxDuration` | 30s | `java.time.Duration` |
| `tls` | `null` | `EtcdTlsConfig` |

!!! note "These are `java.time.Duration`, not `kotlin.time.Duration`"

    `EtcdConnectionConfig` is the one place in the library that uses `java.time.Duration`
    — it is the type the underlying jetcd builder wants. The recipes themselves take
    `kotlin.time.Duration` (with `(long, TimeUnit)` overloads for Java). If Kotlin tells
    you `5.seconds` is the wrong type here, that is why: you want `Duration.ofSeconds(5)`.

### Namespaces

Setting `namespace = "/prod"` transparently prefixes every key this client touches. A
recipe at `/locks/orders` really lives at `/prod/locks/orders`, and nothing in your code
changes. It is the cleanest way to share one etcd cluster between environments or tenants.

!!! tip

    The namespace is a property of the *client*, so it applies uniformly to every recipe
    built on it. Two clients with different namespaces will never see each other's keys —
    which is exactly what you want for isolation, and exactly the trap if you accidentally
    point a reader and a writer at different namespaces.

### TLS and authentication

`EtcdTlsConfig(caCertPath, clientCertPath, clientKeyPath)` — all three are optional:
supply just `caCertPath` to verify the server against a private CA, or all three for
mutual TLS. Username/password auth is orthogonal and set via `user`/`password`.

!!! warning "Don't put the password in source"

    The snippet reads `ETCD_PASSWORD` from the environment. Do likewise, or pull it from
    whatever secret manager you already run.

## Dropping to the jetcd builder

Anything the config class does not model is reachable through the builder receiver:

```kotlin
--8<-- "kotlin/website/gettingstarted/ConnectSnippets.kt:builder"
```

This is the library's general stance: it is a [thin layer](../basics/index.md) over jetcd,
and jetcd is never hidden from you.

## Health checks

`Client.ping()` performs an active, count-only GET and returns a `Boolean`:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/gettingstarted/ConnectSnippets.kt:ping"
    ```

=== "Java"

    ```java
    --8<-- "java/website/gettingstarted/ConnectSnippets.java:ping"
    ```

It takes an optional `RpcResilience` so you can decide whether a health check retries.
For a liveness endpoint you usually do **not** want retries — you want a fast, honest
answer:

```kotlin
client.ping(RpcResilience.DISABLED)
```

Recipes expose their own `ping()` plus a passive `isHealthy()`. The difference matters:
`isHealthy()` reports what the recipe already knows from its connection state without a
round trip, while `ping()` goes and asks. See
[Connection state](../resilience/connection-state.md).

The [Spring Boot starter](../integrations/spring-boot.md) wires `ping()` into an Actuator
health indicator for you.

## Next

- [Core concepts](concepts.md) — the lifecycle every recipe shares
- [Resilience](../resilience/index.md) — retries, timeouts, and reconnection
