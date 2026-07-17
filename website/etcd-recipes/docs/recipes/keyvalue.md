# Transient key/values

`TransientKeyValue` publishes a key that exists only while your process is alive to say so.
It grants a lease, puts the key under it, and keeps the lease renewed in the background.
Stop renewing — because you closed the recipe, or because your process died — and etcd
removes the key when the TTL runs out.

That is the primitive behind presence: "node-1 is up and reachable at this address" is a
claim nobody else can make on your behalf, and one that has to expire on its own when you
stop making it.

It extends `EtcdConnector`, so it shares the lifecycle, exception, and connection-state
surface described in [Core concepts](../getting-started/concepts.md).

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/keyvalue/TransientKeyValueSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/keyvalue/TransientKeyValueSnippets.java:basic"
    ```

## `autoStart = true` is the exception to the constructor rule

Every other recipe in this library has an RPC-free constructor: you construct it, and
nothing touches the network until `start()`. `TransientKeyValue` breaks that rule by
default.

!!! warning "Constructing a `TransientKeyValue` starts the keep-alive"

    With `autoStart = true` — the default — the constructor grants the lease, puts the
    key, and starts the renewal loop before it returns. Three consequences worth knowing
    before you write the line:

    - **It needs a live etcd.** Constructing one in a unit test, or in a Spring bean
      graph that assembles before etcd is reachable, will throw. The constructor
      propagates a setup failure as `EtcdRecipeRuntimeException` rather than handing you
      a recipe that looks fine and publishes nothing.
    - **You cannot register a lease listener in time.** The keep-alive is already running
      when you get the reference, so `addLeaseListener` after construction may miss early
      events.
    - **The reference you never received still owns resources.** The constructor
      shuts down its own executor before propagating a startup failure, precisely because
      you have no object to `close()`.

Pass `autoStart = false` to get the ordinary deferred-start behaviour that the rest of the
library gives you:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/keyvalue/TransientKeyValueSnippets.kt:deferred-start"
    ```

=== "Java"

    ```java
    --8<-- "java/website/keyvalue/TransientKeyValueSnippets.java:deferred-start"
    ```

`start()` is `@Synchronized` and one-shot: a second call throws
`EtcdRecipeRuntimeException`, as does calling it after `close()`. It blocks until the
keep-alive is actually running (or has failed), so when it returns without throwing, the
key is published.

The full parameter list is
`(client, keyPath, keyValue, leaseTtlSecs, autoStart, userExecutor, clientId, resilience)`.
`leaseTtlSecs` is how long the key survives your silence — the shorter it is, the faster
your absence is noticed and the more renewal traffic you generate. `clientId` defaults to a
generated identifier and tags this publisher in the recipe's logs. `userExecutor` lets you
supply the thread the keep-alive parks on; leave it `null` and the recipe owns a
single-thread executor it shuts down on `close()`.

## This lease is healed

A lease that confers *exclusivity* — a lock hold, a semaphore permit, an election's
leadership key — is deliberately never healed: if it expired, etcd already promoted someone
else, and re-granting would mean two holders. A transient key/value has no such problem —
publishing your own address is not an exclusive claim, and nobody else is competing to be
you — so this lease **is** self-healed.

See [Leases and loss](../resilience/leases.md) for the full split, including why a single
`LeaderSelector` sits on both sides of it.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/keyvalue/TransientKeyValueSnippets.kt:lease-listener"
    ```

=== "Java"

    ```java
    --8<-- "java/website/keyvalue/TransientKeyValueSnippets.java:lease-listener"
    ```

If a partition outlasts the TTL, the key vanishes from etcd and the healer re-grants a
lease and re-puts it. Without that, the recipe would sit there looking healthy while
publishing nothing — the single worst failure mode a presence primitive can have.

The `LeaseEvent`s tell you which of those happened:

| Event | Meaning |
| --- | --- |
| `Suspended` | The keep-alive stream hit a transient error; jetcd is retrying it. The lease is still alive. |
| `Expired` | The lease is gone and the key with it. Healing starts. |
| `Restored` | A replacement lease was granted and the key re-published. |
| `Failed` | Healing was abandoned. The key is gone and stays gone. |

All four drive `connectionState`, and the three that mean trouble — `Suspended`, `Expired`,
`Failed` — are also recorded on `exceptions`, so a caller polling `exceptions` sees renewal
problems without registering anything. (`Restored` is good news, so it is logged rather
than recorded.) `Failed` is the one to alert on. See
[Leases and loss](../resilience/leases.md).

!!! note "Healing is not free of consequence"

    Between `Expired` and `Restored` the key genuinely does not exist. Anything watching
    your prefix — a [`PathChildrenCache`](caches.md), a service discovery cache — sees a
    removal followed by an addition, not a hiccup. If that flap matters to your consumers,
    a longer TTL costs you slower failure detection and buys you fewer false removals.

Set `resilience.lease` to a `LeaseResilience` with `RetryPolicy.never` to disable healing
and restore the pre-0.12 behaviour, where an expired lease meant the key was gone for good:

```kotlin
--8<-- "kotlin/website/keyvalue/TransientKeyValueSnippets.kt:resilience"
```

## Scoped use

```kotlin
--8<-- "kotlin/website/keyvalue/TransientKeyValueSnippets.kt:scoped"
```

The key is published for the duration of the block and dropped on the way out.

!!! note "`withTransientKeyValue` does not expose `resilience`"

    Its parameters are
    `(client, keyPath, keyValue, leaseTtlSecs, autoStart, userExecutor, clientId, receiver)`.
    Construct `TransientKeyValue` directly when you need a non-default `ResilienceConfig`.

## `TypedTransientKeyValue<T>`

Publish a typed payload instead of a hand-marshalled `String`:

```kotlin
--8<-- "kotlin/website/keyvalue/TransientKeyValueSnippets.kt:typed"
```

The value is encoded **once**, at construction, and that encoded string is what the
keep-alive re-publishes for the recipe's lifetime — including across healing. There is no
setter: a transient key/value is a standing claim, not a mutable cell. To publish something
new, close this one and construct another.

Because the underlying recipe publishes a `String`, the codec must produce UTF-8 text:
`StringCodec`, `jsonCodec<T>()`, or the Jackson codec from `etcd-recipes-jackson`. A binary
codec will not do. Read it back with the typed `getValue(key, codec)`. See
[Typed values](../typed-values.md).

!!! note "It is a decorator, not a connector"

    `TypedTransientKeyValue<T>` implements `Closeable` and does **not** extend
    `EtcdConnector`. `exceptions`, `isHealthy()`, `connectionState`, `ping()` and the rest
    of the connector surface live on `untyped`, which is a public property and the
    documented escape hatch. `start`, `addLeaseListener` and `close` are delegated
    directly, so the common path needs no unwrapping.

```kotlin
--8<-- "kotlin/website/keyvalue/TransientKeyValueSnippets.kt:typed-scoped"
```

`withTypedTransientKeyValue` — unlike its untyped counterpart — does take a `resilience`
argument.

## Coroutines

`leaseEventsAsFlow()` exposes the lease lifecycle as a `Flow<LeaseEvent>` instead of a
listener. Collecting registers a listener and cancelling removes it; it never starts or
closes the recipe. See [Flows](../coroutines/flows.md).

## Related

If you are publishing a key so that other services can find you, look at
[Service discovery](discovery.md) first. It solves the same presence problem with the same
self-healing lease machinery, and adds the registry, the caches, and the provider
strategies you would otherwise write on top of this. Reach for `TransientKeyValue`
directly when you want presence without the service model.
