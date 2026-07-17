# Ktor

`etcd-recipes-ktor` is an application plugin that connects to etcd for the server's
lifetime and hands the connection to your routes.

```kotlin
implementation("com.pambrose:etcd-recipes-ktor:0.12.0")
```

!!! note "Kotlin only"

    Ktor is a Kotlin framework, and the plugin's consumer API is a pair of extension
    properties on `Application`. There is no Java tab on this page because there is no
    sensible Java story — Java callers on a JVM server stack want the
    [Spring Boot starter](spring-boot.md), or plain `connectToEtcd` and an `EtcdRecipes`
    of their own.

## Installing

```kotlin
--8<-- "kotlin/website/ktor/KtorSnippets.kt:install"
```

`EtcdPluginConfig` is small on purpose — it is the subset of
[`EtcdConnectionConfig`](index.md#etcdconnectionconfig) that a plugin block can express
plainly:

| Field | Type | Notes |
| --- | --- | --- |
| `endpoints` | `List<String>` | Empty by default; set it unless you inject a `client` |
| `user` | `String?` | |
| `password` | `String?` | |
| `namespace` | `String?` | Prefixes every key this client touches |
| `client` | `Client?` | A pre-built client — see [ownership](#who-closes-the-client), below |

```kotlin
--8<-- "kotlin/website/ktor/KtorSnippets.kt:auth"
```

The plugin builds its client through `connectToEtcd`, so it inherits the recipe defaults
(a 5-second connect timeout and a 30-second retry ceiling) described in
[Connecting](../getting-started/connecting.md). `connectTimeout`, `retryMaxDuration`, and
TLS are not exposed in the plugin block — if you need them, build the client yourself and
inject it.

## Who closes the client

This is the one rule to get right, and it is decided entirely by which fields you set.

!!! warning "Set connection fields and the plugin owns the client. Inject one and it doesn't."

    **Supply `endpoints` (etc.)** — the plugin builds the client, and closes it on
    `ApplicationStopping`. You do nothing.

    **Supply `client`** — the plugin installs it and *never* closes it, because it did not
    create it. Closing it is your job, and if you forget, the connection outlives the
    server.

    The plugin will not guess. It closes exactly what it created and nothing else, which
    is the only rule that is safe when the injected client is shared with the rest of your
    application — closing a client out from under its other users would be far worse than
    leaking one.

```kotlin
--8<-- "kotlin/website/ktor/KtorSnippets.kt:own-client"
```

Inject a client when you need builder options the plugin block does not model (TLS,
timeouts, interceptors), when the client is shared with non-Ktor code, or when a test
needs to hand in a fake.

## Using it

Two extension properties on `Application`:

```kotlin
val Application.etcdClient: Client
val Application.etcdRecipes: EtcdRecipes
```

`etcdClient` is the raw jetcd client the plugin installed, for the extension-level API
(`putValue`, `watcher`, transactions):

```kotlin
--8<-- "kotlin/website/ktor/KtorSnippets.kt:consume-client"
```

`etcdRecipes` is an [`EtcdRecipes`](index.md#etcdrecipes) factory bound to that client:

```kotlin
--8<-- "kotlin/website/ktor/KtorSnippets.kt:consume-recipes"
```

!!! note "`etcdRecipes` allocates on every access"

    It is a `get()` that news up an `EtcdRecipes` each time, not a cached instance. That
    is cheap — the factory holds a client reference and nothing else — but it means the
    identity is not stable, so do not use it as a map key or compare it by reference.
    `etcdClient` *is* stable; it is stored in the application's attributes.

    The recipes it builds are not owned by the plugin either. As everywhere else,
    `start()`/`close()` is yours: scope short-lived recipes to the request or the unit of
    work, and hold long-lived ones (a cache, a `LeaderLatch`) yourself, closing them on
    `ApplicationStopping` alongside the plugin's own teardown.
