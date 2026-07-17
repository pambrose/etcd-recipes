# Service discovery

Service discovery is two problems wearing one name: publishing where a service instance
lives, and finding one to talk to. etcd-recipes splits them into separate types and gives
you a façade over both:

| Type | Side | Use it when |
| --- | --- | --- |
| [`ServiceRegistry`](#serviceregistry) | Write | You only publish instances |
| [`ServiceCache`](#servicecache) | Read | You want a live, watch-maintained instance list |
| [`ServiceProvider`](#serviceprovider) | Read | You want one instance to call, with load balancing |
| [`ServiceDiscovery`](#servicediscovery) | Both | You want the whole thing behind one object |

All four extend `EtcdConnector`, so they share the lifecycle, exception, and
connection-state surface described in [Core concepts](../getting-started/concepts.md).

Every key this recipe writes lives under `<servicePath>/names`, one key per instance at
`<servicePath>/names/<serviceName>/<id>`. That layout is why the read-side types accept a
*names* path rather than the service path — more on that below.

## `ServiceInstance`

`ServiceInstance` is what gets written to etcd: a `@Serializable` Kotlin data class whose
JSON is the wire format.

```kotlin
@Serializable
data class ServiceInstance(
  val name: String,
  var jsonPayload: String,
  var address: String = "",
  var port: Int = -1,
  var sslPort: Int = -1,
  var registrationTimeUTC: Long = Instant.now().toEpochMilli(),
  var serviceType: ServiceType = ServiceType.DYNAMIC,
  var uri: String = "",
  var enabled: Boolean = true,
) {
  val id: String = randomId(TOKEN_LENGTH)
}
```

The fields it does not understand live in `jsonPayload`, an opaque `String` the library
never parses. `id` is random and assigned at construction: it is the last segment of the
instance's etcd key, and the `id` that `queryForInstance(name, id)` takes.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/DiscoverySnippets.kt:builder"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:builder"
    ```

!!! note "`ServiceInstance` is not generic"

    Curator's `ServiceInstance<T>` carries a typed payload; this one does not. The payload
    is a `String`, and typing is layered on top of it by extension functions (below) rather
    than baked into the class. That keeps one wire format for every caller: a Kotlin client
    using a typed payload and a Java client reading the raw string see the same bytes.

`toJson()` and the `@JvmStatic toObject(json)` are the round trip. `newBuilder(name, jsonPayload)`
is the Java-facing constructor path; Kotlin has the `serviceInstance(name, jsonPayload) { }`
DSL, whose lambda runs against a `ServiceInstanceBuilder`.

### Typed payloads

`ServiceInstance.payload(codec)`, `setPayload(value, codec)`, and the
`serviceInstance(name, payload, codec) { }` builder let you treat `jsonPayload` as a typed
value without changing what lands in etcd:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/DiscoverySnippets.kt:typed"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:typed"
    ```

!!! warning "Text codecs only"

    `jsonPayload` is a `String`, so the codec must produce UTF-8 text: `StringCodec`,
    `jsonCodec<T>()`, or the Jackson module's JSON codec. A binary codec is **unsupported**
    here — its bytes cannot survive the trip through a `String` field. This is the one place
    in the library where [typed values](../typed-values.md) are not codec-agnostic.

The wire format of `toJson()` is byte-for-byte unchanged by typing, so adopting a typed
payload does not break clients still reading `jsonPayload` as a raw string. In Java, note
that `jsonCodec()` is `inline reified` and therefore Kotlin-only; the extensions themselves
are reachable as statics on `TypedServiceInstances`. See the [Java guide](../java.md).

### `ServiceType`

```kotlin
enum class ServiceType { DYNAMIC, STATIC, PERMANENT, DYNAMIC_SEQUENTIAL }
```

`serviceType.isDynamic` is true for `DYNAMIC` and `DYNAMIC_SEQUENTIAL`. The enum is
metadata you publish for your own consumers: the recipes do not branch on it. Every
registration is lease-bound regardless of what the field says.

## `ServiceDiscovery`

The façade. It owns a `ServiceRegistry` internally, hands out caches and providers, and
closes all of them when it closes.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/DiscoverySnippets.kt:register"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:register"
    ```

`registerService`, `updateService`, and `unregisterService` all throw `EtcdRecipeException`:
registration is a compare-and-swap that can lose, an update to something never registered is
a caller bug, and both are worth failing loudly rather than silently no-oping.

The direct queries are one-shot reads — no cache, no watch, a range GET per call:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/DiscoverySnippets.kt:query"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:query"
    ```

`withServiceDiscovery`, `withServiceCache`, and `withServiceProvider` are the scoped Kotlin
forms:

```kotlin
--8<-- "kotlin/website/discovery/DiscoverySnippets.kt:scoped"
```

!!! note "The constructor parameter is `resilienceConfig`, not `resilience`"

    Every other recipe names it `resilience`. `ServiceDiscovery` names it `resilienceConfig`,
    so a Kotlin caller passing it by name has to spell it differently here. It is the same
    `ResilienceConfig`, and it is forwarded to the registry and to every provider the façade
    creates — but **not** to a cache from `serviceCache(name)`, which is built with
    `ResilienceConfig.DEFAULT`. Construct that `ServiceCache` yourself if it needs a custom
    config. The scoped `withServiceDiscovery` helper does not take a config at all, so a
    non-default one means constructing `ServiceDiscovery` directly.

## `ServiceRegistry`

The write side on its own. Depend on it when a process only publishes itself and never
looks anything up — the read side brings watches and background threads a publisher has no
use for.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/DiscoverySnippets.kt:registry"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:registry"
    ```

### Registration leases self-heal

This is the opposite choice from the one [locks](locks.md) make, and the reason is worth
stating plainly.

A lock's lease is deliberately never healed: if it expires, etcd has already given the lock
to somebody else, and reclaiming it would corrupt mutual exclusion. A *registration* has no
such rival. Nobody else is trying to be your instance id, so when the lease expires the only
correct behaviour is to put the key back:

- on expiry the healer grants a new lease and re-runs the registration CAS,
- `LeaseEvent.Expired` then `LeaseEvent.Restored` arrive at every `addLeaseListener`,
- if healing is abandoned you get `LeaseEvent.Failed`, and the instance is genuinely gone
  from etcd until you re-register it.

The window between expiry and heal is real: for a few seconds your instance is absent from
every consumer's cache and will not be selected. That is correct — a partitioned instance
probably *should* not be selected — but it means registration is not a fire-and-forget call
you can stop watching. See [Leases and loss](../resilience/leases.md).

## `ServiceCache`

The read side, backed by a watch. `start()` takes a consistent snapshot, then anchors the
watch at that exact revision so there is no overlap and no gap. After that, `instances` is
an in-memory read.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/DiscoverySnippets.kt:cache"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:cache"
    ```

`start()` is one-shot: calling it twice throws `EtcdRecipeRuntimeException`. Register
listeners first — the initial snapshot does not replay through `addListenerForChanges`, so
a listener added afterwards only sees changes from that point on. Read `instances` for the
current set; use the listener for the deltas.

`addRecoveryListener` reports the watch's own health: resubscribes after a stream death,
resyncs after compaction (the cache reconciles itself against a fresh snapshot), and the
`Failed` event that means recovery was abandoned and the instance list is now frozen and
lying to you.

```kotlin
--8<-- "kotlin/website/discovery/DiscoverySnippets.kt:standalone-cache"
```

!!! warning "A standalone cache takes the `names` path, not the service path"

    `ServiceCache(client, namesPath, serviceName)` — that middle argument is
    `<servicePath>/names`, which is what `ServiceDiscovery.serviceCache(name)` passes for
    you. Handing it the bare service path compiles fine and then watches a prefix nothing
    is ever written to, so the cache stays empty forever. The same applies to a directly
    constructed `ServiceProvider`.

## `ServiceProvider`

A client-side load balancer: it takes the cache's instance list, applies a
[strategy](#strategies), and hands you one instance to call.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/ServiceProviderSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:provider"
    ```

`getInstance()` throws `EtcdRecipeException` when nothing is available — whether because
nothing is registered or because everything has been ejected. It does not return null:
"no instance to call" is an error your request path has to handle, not a value to
`?.`-away.

### Two read modes

`start()` is optional, and what it changes is where reads come from:

| | Before `start()` | After `start()` |
| --- | --- | --- |
| `getAllInstances()` / `getInstance()` | A direct range GET per call | An in-memory cache read |
| Cost | A round trip per selection | A watch, maintained in the background |
| Freshness | Current as of this instant | Current as of the last watch event |

```kotlin
--8<-- "kotlin/website/discovery/ServiceProviderSnippets.kt:direct"
```

Use the direct mode for a one-off lookup in a script or a startup path. Use the cache-backed
mode for anything that selects an instance per request — a GET per outbound call turns etcd
into a hard dependency of your request path, which is exactly what a discovery cache exists
to prevent. `start()` is one-shot, and `close()` on an un-started provider is a clean no-op.

### Ejecting failing instances

`noteError(instance)` is how a provider learns that an instance it handed you did not work.
After `errorThreshold` errors (default 3) the instance drops out of selection for
`downPeriod` (default 30 seconds), then rejoins automatically:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/discovery/ServiceProviderSnippets.kt:note-error"
    ```

=== "Java"

    ```java
    --8<-- "java/website/discovery/DiscoverySnippets.java:note-error"
    ```

!!! danger "Ejection is keyed on the instance's *value*"

    The down-list is a map keyed by `ServiceInstance`, and `ServiceInstance` is a data class
    — so two instances are the same key when their fields are equal. Two consequences you
    have to know about:

    **Pass back exactly what `getInstance()` gave you, unmodified.** If you set `port` or
    rewrite `jsonPayload` on the instance before calling `noteError`, you have built a
    different key. Nothing throws. The error is recorded against a value that will never be
    selected again, the failing instance stays in rotation, and you keep calling it.

    **`id` is not part of the key.** It is declared in the class body, not the primary
    constructor, so it is excluded from `equals()`. Two instances registered with identical
    fields share one ejection entry — noting an error against one ejects both.

### Strategies

```kotlin
fun interface ProviderStrategy {
  fun select(instances: List<ServiceInstance>): ServiceInstance?
}
```

Three ship with the library:

| Strategy | Stateful? | Behaviour |
| --- | --- | --- |
| `RandomStrategy` | No (an `object`) | Uniform random. The default |
| `RoundRobinStrategy` | **Yes** | Even rotation via a monotonic cursor |
| `StickyStrategy` | **Yes** | Returns the previous instance while it is still registered |

```kotlin
--8<-- "kotlin/website/discovery/ServiceProviderSnippets.kt:round-robin"
```

```kotlin
--8<-- "kotlin/website/discovery/ServiceProviderSnippets.kt:sticky"
```

The Java form is the ejection example above: `discovery.serviceProvider("worker", new
RoundRobinStrategy(), 3)`. `withServiceProvider` is an inline Kotlin extension, so Java
constructs the provider and closes it itself — see the [Java guide](../java.md).

!!! warning "Never share a `RoundRobinStrategy` or a `StickyStrategy` between providers"

    Both carry mutable state — a cursor, a remembered instance — and neither is namespaced
    by provider. Share one and the providers interleave: a round-robin cursor advanced by
    provider A makes provider B skip instances, and two sticky providers fight over one
    memory. Construct a fresh strategy per provider. `RandomStrategy` is an `object`
    precisely because it is stateless and therefore safe to share.

`ProviderStrategy` is a `fun interface`, so a custom policy is a lambda. Return null when
the list is empty; the provider turns that into the `EtcdRecipeException`:

```kotlin
--8<-- "kotlin/website/discovery/ServiceProviderSnippets.kt:custom-strategy"
```

Strategies see only the *available* instances — the live set minus anything currently
ejected — so a strategy never has to know about `noteError` at all.

A provider built directly rather than through the façade takes the same `names` path caveat
as `ServiceCache`:

```kotlin
--8<-- "kotlin/website/discovery/ServiceProviderSnippets.kt:standalone"
```

## Coroutines

`ServiceDiscovery`'s blocking calls have suspending twins — `awaitRegisterService`,
`awaitUpdateService`, `awaitUnregisterService`, `awaitQueryForNames`, `awaitQueryForInstances`,
`awaitQueryForInstance` — as does `ServiceCache.awaitStart()`. See
[Coroutines](../coroutines/index.md).

The cache is also a natural `Flow` source: `ServiceCache.eventsAsFlow()` and
`recoveryEventsAsFlow()`, plus `ServiceRegistry.leaseEventsAsFlow()` for the healing
lifecycle. Collection registers a listener and cancellation removes it — it never starts or
closes the recipe. See [Flows](../coroutines/flows.md).

## Observability

With the [Micrometer module](../integrations/index.md) wired in, `ServiceCache` reports
`etcd.cache.sync` (a timer over snapshot loads) and `etcd.cache.size`, and its watch
recoveries land on the `etcd.watch.recovery` counter. A registry's healing shows up as
connection-state transitions. See [Observability](../observability.md).
