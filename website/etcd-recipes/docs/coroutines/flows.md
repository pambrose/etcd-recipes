# Flows

The [suspending API](index.md) covers request/response. Everything that etcd or a
recipe *pushes* at you — watch events, cache changes, leadership hand-offs, lease
transitions, connection state, background failures — is exposed as a `Flow`, as the
coroutine-native alternative to registering a listener.

!!! note "This page is Kotlin-only"

    The coroutines layer has no Java equivalent. Every flow on this page wraps a
    listener that Java can register directly against the blocking API described in
    [Recipes](../recipes/index.md); see the [Java guide](../java.md).

## The whole surface

| Flow | Element | Buffering |
| --- | --- | --- |
| `Client.watchAsFlow(keyName, option, resilience, resyncWith, capacity)` | `WatchFlowEvent` | `capacity` |
| `Client.watchEventsAsFlow(keyName, option, resilience, capacity)` | `WatchEvent` | `capacity` |
| `Client.leadershipAsFlow(electionPath, resilience, capacity)` | `LeadershipEvent` | `capacity` |
| `PathChildrenCache.eventsAsFlow(capacity)` | `PathChildrenCacheEvent` | `capacity` |
| `PathChildrenCache.recoveryEventsAsFlow(capacity)` | `WatchRecoveryEvent` | `capacity` |
| `NodeCache<T>.eventsAsFlow(capacity)` | `NodeCacheEvent<T>` | `capacity` |
| `NodeCache<*>.recoveryEventsAsFlow(capacity)` | `WatchRecoveryEvent` | `capacity` |
| `TypedPathChildrenCache<T>.eventsAsFlow(capacity)` | `TypedPathChildrenCacheEvent<T>` | `capacity` |
| `TypedPathChildrenCache<*>.recoveryEventsAsFlow(capacity)` | `WatchRecoveryEvent` | `capacity` |
| `ServiceCache.eventsAsFlow(capacity)` | `ServiceCacheEvent` | `capacity` |
| `ServiceCache.recoveryEventsAsFlow(capacity)` | `WatchRecoveryEvent` | `capacity` |
| `TransientKeyValue.leaseEventsAsFlow(capacity)` | `LeaseEvent` | `capacity` |
| `DistributedWorkQueue.leaseEventsAsFlow(capacity)` | `LeaseEvent` | `capacity` |
| `ServiceRegistry.leaseEventsAsFlow(capacity)` | `LeaseEvent` | `capacity` |
| `EtcdConnector.connectionStateAsFlow()` | `ConnectionState` | conflated |
| `EtcdConnector.backgroundExceptionsAsFlow(capacity)` | `BackgroundException` | `capacity` |
| `EtcdLock.lockLostAsFlow()` | `LockLostEvent` | unbounded, not tunable |
| `DistributedSemaphore.permitLostAsFlow()` | `PermitLostEvent` | unbounded, not tunable |

`capacity` defaults to `Channel.UNLIMITED` everywhere it appears. The next section
explains why, and when to change it.

## The shared contract

Every flow on this page is built the same way, and understanding the pattern once
tells you how all of them behave:

```kotlin
callbackFlow {
  val listener = SomeListener { event -> trySendBlocking(event) }
  addSomeListener(listener)
  awaitClose { removeSomeListener(listener) }
}.buffer(capacity = Channel.UNLIMITED)
```

Four consequences:

**Collecting subscribes; cancelling unsubscribes.** The listener is registered when
collection starts and removed in `awaitClose` when the collector is cancelled or the
flow completes. Nothing leaks, and there is no `close()` for you to remember.

**They never start or close the recipe.** Subscribing to `cache.eventsAsFlow()` does
not start the cache, and cancelling the collector does not close it. Lifecycle stays
where it was — `use { }` and `start()` — and the flow is purely an observer. The one
exception is `watchAsFlow`, which owns its watcher: cancelling the collector closes
it, bounded by the watcher's ≤5-second close contract.

**They are cold.** Each collector gets its own listener and its own buffer. For a hot,
shared stream, use `.shareIn(scope)` / `.stateIn(scope)`.

**Buffering protects the producer, not you.** This is the important one.

### What `capacity` actually means

Every one of these listeners fires on a thread that must not be blocked: jetcd's watch
dispatcher, a recipe's lease-healer thread, or jetcd's lease-callback thread. They
push with `trySendBlocking`, which under the `UNLIMITED` default never blocks — so a
slow collector's backlog grows in memory, and the producing thread sails on.

Setting a bounded `capacity` **opts into backpressure**, and the thing that
experiences that backpressure is the producer's dispatcher thread:

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:capacity"
```

For `watchAsFlow` that thread also runs recovery and resync, so a full buffer stalls
your watch's resubscription, not just its delivery. For a cache it stalls the cache's
view of the world, which means `currentData` goes stale while your collector catches
up.

!!! warning "Bound the capacity only if you have decided a stalled producer is better than an unbounded buffer"

    That is a real trade — an unbounded buffer under a permanently slow collector is
    a memory leak with extra steps. But it is a trade you should make deliberately,
    per flow, knowing that the stall reaches further than the events themselves.
    The usual alternative is to keep `UNLIMITED` and make the collector fast: hand
    work off to another coroutine, or `.conflate()` if you only care about the latest
    element.

## Watches — `WatchFlowEvent.kt`

`watchAsFlow` is the flow face of the resilient watcher: stream deaths are recovered
per the `WatchResilience` config, and after a compaction your `resyncWith` hook is
invoked to re-read the world and hand back a new anchor revision.

Elements are a sealed interface, because responses and recovery transitions arrive
**in-band and in order**:

```kotlin
sealed interface WatchFlowEvent {
  data class Response(val response: WatchResponse) : WatchFlowEvent
  data class Recovery(val event: WatchRecoveryEvent) : WatchFlowEvent
}
```

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:watch-flow"
```

!!! note "Why recovery is in-band"

    A resilient watch that silently hid suspension, resubscription, and compaction
    resync would be a trap for any collector that keeps derived state. "The events
    stopped for nine seconds and then resumed from a different revision" is not a
    detail the library gets to keep to itself — it is exactly the moment your derived
    state became a lie. Delivering `Recovery` in the same ordered stream means you
    learn about the gap *before* the events that follow it.

`resyncWith` is your one chance to reconcile after a compaction: the events between
the compacted revision and the new anchor are gone from etcd and are never coming
back.

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:watch-flow-resync"
```

`watchEventsAsFlow` is the convenience form — it flattens responses into their
`WatchEvent`s and **drops recovery transitions entirely**:

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:watch-events-flow"
```

!!! warning "`watchEventsAsFlow` silently skips the gap"

    Dropping recovery is fine for a stateless collector — one that reacts to each
    event and remembers nothing. It is wrong for a collector that maintains derived
    state, because after a compaction resync this flow just carries on as if no
    events had been lost. If you keep state, use `watchAsFlow`.

See [Watches](../basics/watch.md) for the underlying model and
[Resilience](../resilience/index.md) for the recovery policy.

## Leadership — `ElectionFlows.kt`

```kotlin
sealed interface LeadershipEvent {
  data class Elected(val leaderName: String) : LeadershipEvent
  data object Vacated : LeadershipEvent
  data class WatchFailed(val cause: Throwable?) : LeadershipEvent
}
```

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:leadership-flow"
```

!!! tip "The current leader is emitted immediately on collect"

    `leadershipAsFlow` reads the leader key before subscribing its watcher and emits
    that first, so a collector that starts long after the election was decided is not
    left blind until the next hand-off — which might be hours away, or never. You get
    `Elected(name)` or `Vacated` straight away, then transitions.

    The same re-read happens after every `Resubscribed` or `Resynced`, because a
    hand-off may have happened while the stream was dead. `WatchFailed` means
    observation has stopped for good — the recovery policy was exhausted.

This flow **observes**; it does not participate. To run for election, use
`LeaderSelector` with `awaitStart()` / `awaitLeadershipComplete()` — see
[Leader election](../recipes/election.md).

## Caches — `CacheFlows.kt`

Each cache exposes its events and its watch-recovery transitions as separate flows:

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:cache-flow"
```

!!! danger "Subscribe before you start the cache"

    Events fired while nothing is collecting are **not** buffered — `callbackFlow`
    only registers the listener when collection begins. If you call
    `start(POST_INITIALIZED_EVENT)` and *then* subscribe, the `INITIALIZED` event is
    already gone and you will wait forever for an event that has been and passed.
    Launch the collector first (and give it a moment, or signal from `onStart`), then
    start the cache.

`NodeCache<T>` and `TypedPathChildrenCache<T>` decode through their codec, so their
flows carry typed elements:

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:node-cache-flow"
```

See [Caches](../recipes/caches.md) and [Typed values](../typed-values.md).

## Service discovery — `DiscoveryFlows.kt`

`ServiceCacheEvent` flattens the four-argument `ServiceCacheListener` callback into
one data class:

```kotlin
data class ServiceCacheEvent(
  val eventType: WatchEvent.EventType,
  val isAdd: Boolean,
  val serviceName: String,
  val serviceInstance: ServiceInstance?,
)
```

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:discovery-flow"
```

See [Service discovery](../recipes/discovery.md).

## Lock and permit loss — `LockFlows.kt`

```kotlin
data class LockLostEvent(val cause: Throwable?)
data class PermitLostEvent(val cause: Throwable?)
```

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:lock-lost-flow"
```

!!! note "These two take no `capacity` — deliberately"

    Every other flow here lets you opt into backpressure. These do not, because their
    listeners fire on jetcd's **lease-callback thread**, which must never block: park
    it and you stop the keep-alives for every lease on that client, which is a
    spectacular way to turn one lost lock into all of them. The channel is therefore
    unconditionally unbounded, and no argument is offered that could make it
    otherwise.

    The practical consequence is small — lock loss is rare and one event per loss is
    not a volume problem — but it is why the signature differs.

Lock loss itself is covered in [Locks](../recipes/locks.md); the lease mechanics are
in [Leases and loss](../resilience/leases.md).

## Lease events — `LeaseFlows.kt`

The recipes that own a self-healing lease — `TransientKeyValue`,
`DistributedWorkQueue`, and `ServiceRegistry` — publish its lifecycle:

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:lease-flow"
```

`Suspended` → the keep-alive stream hit a transient error. `Expired` → the lease is
gone and ownership of its keys may have moved to someone else. `Restored` → a
replacement lease was granted and the keys re-established. `Failed` → healing was
abandoned. See [Leases and loss](../resilience/leases.md).

## Connection state and background failures

`connectionStateAsFlow` and `backgroundExceptionsAsFlow` are on `EtcdConnector`, so
they are available on every stateful recipe:

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:connection-flow"
```

`connectionStateAsFlow` **emits the current state on collect** — same reasoning as
`leadershipAsFlow`: a late subscriber should not have to wait for a transition to
learn where it stands. It is `.conflate()`d, so a slow collector sees the latest state
rather than every intermediate one. That is the right default for state: nobody
benefits from processing a stale `SUSPENDED` after `RECONNECTED` has already landed.
It is also cold — `.stateIn(scope)` gives you a shared `StateFlow`.

`backgroundExceptionsAsFlow` is the push counterpart to the pull-only
`EtcdConnector.exceptions` list. Its listener fires on the reporting recipe's own
thread, so it is buffered: a failure is never dropped, and the reporting thread never
blocks.

See [Connection state](../resilience/connection-state.md).

## Composing under structured concurrency

Flows compose the way you would hope: one parent job per subscription set, and
cancelling the parent unsubscribes everything — each `awaitClose` removes its listener
or closes its watcher.

```kotlin
--8<-- "kotlin/website/coroutines/FlowSnippets.kt:composed"
```

!!! tip "One coroutine per flow, not one `when` over a merged stream"

    `merge()` works, but it forces a common element type and couples the collectors'
    failure modes: a slow branch backs up the others' buffers, and an exception in one
    kills them all. Separate `launch`es under one parent give you the cancellation
    story you want with none of that.
