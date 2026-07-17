# Caches

A cache turns a watch into a local read. Instead of asking etcd for a value every time
you need it, you snapshot once, keep a watch open, and read from memory — so the hot
path never blocks on a network round trip.

etcd-recipes ships three:

| Recipe | Caches | Typed |
| --- | --- | --- |
| [`NodeCache<T>`](#nodecachet) | One key | Always — takes an `EtcdCodec<T>` |
| [`PathChildrenCache`](#pathchildrencache) | Every child of a prefix | No — raw `ByteSequence` |
| [`TypedPathChildrenCache<T>`](#typedpathchildrencachet) | Every child of a prefix | Yes — decorates the above |

`NodeCache` and `PathChildrenCache` extend `EtcdConnector`, so they share the lifecycle,
exception, and connection-state surface described in
[Core concepts](../getting-started/concepts.md). `TypedPathChildrenCache` does not — see
[below](#typedpathchildrencachet).

## Listeners run on a watch dispatcher

Read this before you write a listener.

!!! danger "Never block a cache listener on anything that needs etcd"

    Watcher callbacks originate on jetcd's Vert.x event loop. Anything that needs
    another gRPC response — a `get`, a `put`, a lock acquisition — or that contends for
    a lock the caller holds *while that caller is waiting on gRPC*, deadlocks the event
    loop. Not "slows it down": stops it, for every watch in the process, permanently.

    The library's `Client.watcher` extension hops callbacks onto a dedicated
    single-thread executor, which buys back the simple cases. It does **not** make
    listeners safe in general: a listener that blocks on a mutex held by a thread that
    is itself parked on an etcd call still hangs the dispatcher, and once the dispatcher
    hangs the cache stops converging while continuing to look healthy.

    Treat a listener as a notification, not a workflow. Copy what you need, hand it to
    your own executor, and return.

Each cache gets its own dispatcher thread, and it is single-threaded on purpose: that is
what makes events arrive in etcd's order. A slow listener therefore delays every later
event for that cache — and the watch-recovery loop, including its resync `GET`, runs on
that same thread. Keep listeners short even when they cannot deadlock.

## `NodeCache<T>`

One key, kept hot, decoded through an `EtcdCodec<T>`. This is the "watch my config value"
recipe.

```kotlin
--8<-- "kotlin/website/cache/NodeCacheSnippets.kt:basic"
```

`NodeCache<T>` is generic and takes a codec, so it is presented in Kotlin throughout this
section. It is callable from Java — see the [Java guide](../java.md) — but the codec
generics make it markedly less pleasant there; Java callers usually want
`PathChildrenCache`.

### Why `start()` snapshots first, then anchors the watch

`start()` is one-shot: it throws if called twice, or after `close()`. What it does in
between matters more than it looks.

It reads the key, remembers the revision that read was answered at, and then opens the
watch at **snapshot revision + 1**. That ordering is the whole recipe:

- Watch first, snapshot second, and a `PUT` landing in between gets seen by the watch and
  then *overwritten* by the older snapshot. The cache silently serves a stale value
  forever.
- Snapshot first, watch from "now", and a `PUT` landing in between is missed by both. Same
  outcome, different race.
- Snapshot first, watch from the snapshot's revision + 1, and the stream picks up exactly
  where the snapshot left off — no gap, no overlap.

This is etcd's standard bootstrap, and the same fix is applied everywhere this library
establishes a watch. It is also why compaction of the watched revision re-syncs
transparently: the resync is the same snapshot-and-re-anchor operation.

### Reading

`current` decodes on read and returns `null` when the key is absent. `currentBytes` hands
back the raw `ByteSequence` and skips the codec.

```kotlin
--8<-- "kotlin/website/cache/NodeCacheSnippets.kt:typed"
```

!!! note "`current` decodes at your call site"

    Decoding lazily means a malformed payload throws where you read it, not on the
    dispatcher thread where nobody is listening. Events are the other way round: a
    payload that fails to decode in the listener path is recorded on `exceptions` and the
    event is skipped, because killing the dispatcher over one bad value would stop the
    cache updating at all.

### Listening

```kotlin
--8<-- "kotlin/website/cache/NodeCacheSnippets.kt:listener"
```

`NodeCacheEvent<T>` carries a `type` — `CREATED`, `UPDATED`, or `DELETED` — and the decoded
`value`, which is `null` on `DELETED`. `CREATED` means "the cache had no value and now
does", which is a statement about *the cache*, not about etcd: a key that already existed
when you called `start()` produces `UPDATED` on its next change, because the snapshot
already put a value in hand.

Register listeners before `start()`. The watch begins delivering the moment `start()`
returns, and nothing replays what you were not listening for.

### Watch recovery

```kotlin
--8<-- "kotlin/website/cache/NodeCacheSnippets.kt:recovery"
```

A `WatchRecoveryEvent.Failed` is the one that matters: recovery has been abandoned, so
`current` is frozen at whatever it last saw and will never move again. It is also recorded
on `exceptions` and drives `connectionState` to `LOST`. See
[Resilience](../resilience/index.md).

### Scoped use

```kotlin
--8<-- "kotlin/website/cache/NodeCacheSnippets.kt:scoped"
```

## `PathChildrenCache`

A whole prefix. Every child of `cachePath` is held in memory and kept current by one
prefix watch.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/cache/PathChildrenCacheSnippets.java:basic"
    ```

### Start modes

`StartMode` decides what the cache knows when `start()` returns:

| Mode | Snapshot | `INITIALIZED` event |
| --- | --- | --- |
| `NORMAL` | No — starts empty | No |
| `BUILD_INITIAL_CACHE` | Yes | No |
| `POST_INITIALIZED_EVENT` | Yes | Yes, carrying the snapshot |

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:start-modes"
    ```

=== "Java"

    ```java
    --8<-- "java/website/cache/PathChildrenCacheSnippets.java:start-modes"
    ```

There are two `start()` overloads. `start(buildInitial, waitOnStartComplete)` is the
convenience form — `buildInitial = true` maps to `BUILD_INITIAL_CACHE`, `false` to
`NORMAL` — and `start(mode, waitOnStartComplete)` is the one that can reach
`POST_INITIALIZED_EVENT`. Both default `waitOnStartComplete` to `true`.

!!! warning "`NORMAL` does not start with what is already there"

    `NORMAL` skips the snapshot entirely and watches from now. Children that existed
    before you started are invisible until something touches them. That is occasionally
    what you want (you only care about *changes*), and almost never what people mean when
    they reach for a cache. Prefer `BUILD_INITIAL_CACHE`.

### Waiting for the snapshot

In the two priming modes the snapshot loads on a background thread.
`waitOnStartComplete = true` (the default) makes `start()` block until it has finished, so
`currentData` is populated when you get control back. Pass `false` when `start()` must not
block your caller, then bound the wait yourself:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:wait-on-start"
    ```

=== "Java"

    ```java
    --8<-- "java/website/cache/PathChildrenCacheSnippets.java:wait-on-start"
    ```

`waitOnStartComplete()` with no argument waits indefinitely; the `Duration` and
`(long, TimeUnit)` overloads bound it and return `false` on timeout. All three throw
`InterruptedException`.

### Reading

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:reads"
    ```

=== "Java"

    ```java
    --8<-- "java/website/cache/PathChildrenCacheSnippets.java:reads"
    ```

`currentData` returns `List<ChildData>` sorted by child name, so iteration order is stable
across clients and across restarts. `currentDataAsMap` is an unsorted point-in-time copy.

!!! warning "`getCurrentData()` takes a child name, not a path"

    The keys are relative to `cachePath`: `"w1"`, not `"/cache/workers/w1"`. Passing a
    full path returns `null` — silently, because a missing child is a legitimate answer.
    This trips people up exactly once.

### Listening

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:listener"
    ```

=== "Java"

    ```java
    --8<-- "java/website/cache/PathChildrenCacheSnippets.java:listener"
    ```

`PathChildrenCacheEvent` has a `childName`, a `type` — `CHILD_ADDED`, `CHILD_UPDATED`,
`CHILD_REMOVED`, `INITIALIZED` — and `data`. On `CHILD_REMOVED`, `data` is the value the
child held immediately before removal, which is usually what you need to clean up after
it. `initialData` is populated only on `INITIALIZED` and is empty on every other event.

### `rebuild()` and `clear()`

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:rebuild"
    ```

=== "Java"

    ```java
    --8<-- "java/website/cache/PathChildrenCacheSnippets.java:rebuild"
    ```

`rebuild()` re-snapshots the prefix and reconciles the live map in place — it does not
clear first, so `currentData` never reports an empty window mid-rebuild. `clear()` drops
every entry locally without touching etcd.

!!! note "`rebuild()` is a repair tool, not a refresh"

    The watcher already keeps the cache converged, including across compaction. A manual
    `rebuild()` races the live watch on any key that changes during the snapshot, and last
    writer wins — so it can momentarily *un*-apply an event you already saw. Rely on the
    watcher for ordering; reach for `rebuild()` when you have reason to believe the cache
    drifted.

### Watch recovery and resilience

```kotlin
--8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:recovery"
```

`addRecoveryListener` reports resubscribes after fatal stream deaths, compaction resyncs,
and abandonment. `Failed` means the cache is now frozen: it will keep answering reads with
data that no longer reflects etcd. That is the failure mode worth alerting on, and it is
why the event is also recorded on `exceptions` and moves `connectionState` to `LOST`. See
[Resilience](../resilience/index.md) and [Observability](../observability.md).

### Scoped use

```kotlin
--8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:scoped"
```

!!! note "`withPathChildrenCache` does not expose `resilience`"

    Its parameters are `(client, cachePath, userExecutor, receiver)` — there is no
    `resilience` argument on this overload. Construct `PathChildrenCache` directly when
    you need a non-default `ResilienceConfig`:

    ```kotlin
    --8<-- "kotlin/website/cache/PathChildrenCacheSnippets.kt:resilience"
    ```

## `TypedPathChildrenCache<T>`

The same prefix cache with every child value decoded through an `EtcdCodec<T>`:
`currentData` yields `TypedChildData<T>`, `getCurrentData()` yields `T?`, and
`currentDataAsMap` yields `Map<String, T>`.

```kotlin
--8<-- "kotlin/website/cache/TypedCacheSnippets.kt:basic"
```

See [Typed values](../typed-values.md) for the codec options.

### It is a decorator, not a connector

`TypedPathChildrenCache<T>` implements `Closeable`. It does **not** extend
`EtcdConnector`. That means `exceptions`, `hasExceptions`, `isHealthy()`,
`connectionState`, `ping()`, and the connection-state listeners are not on it.

They are all on `untyped`, which is a public property and the documented escape hatch:

```kotlin
--8<-- "kotlin/website/cache/TypedCacheSnippets.kt:untyped"
```

This matters more than the usual "there's an escape hatch if you need it", because of how
decode failures surface. A payload the codec cannot read is caught in the re-emit path,
recorded on `untyped.exceptions`, and its event is skipped. The typed listener simply
never fires for that child. If you are not reading `untyped.exceptions`, a poison value in
your prefix looks exactly like a child that never changed.

### Listening

```kotlin
--8<-- "kotlin/website/cache/TypedCacheSnippets.kt:listener"
```

!!! note "`TypedPathChildrenCacheEvent<T>` reuses the untyped `Type` enum"

    There is no `TypedPathChildrenCacheEvent.Type`. The event's `type` is a
    `PathChildrenCacheEvent.Type` — the same `CHILD_ADDED` / `CHILD_UPDATED` /
    `CHILD_REMOVED` / `INITIALIZED` values. Only the payload is typed: `data` is `T?` and
    `initialData` is `List<TypedChildData<T>>`.

### Scoped use

```kotlin
--8<-- "kotlin/website/cache/TypedCacheSnippets.kt:scoped"
```

Unlike its untyped counterpart, `withTypedPathChildrenCache` *does* take a `resilience`
argument, after `userExecutor`.

## Coroutines

All three caches expose their events as flows — `eventsAsFlow()` and
`recoveryEventsAsFlow()` — which is the better answer to the dispatcher problem at the top
of this page. A flow buffers (unlimited by default), so a slow collector cannot stall the
watch dispatcher the way a slow listener does, and collection happens on your dispatcher
rather than the cache's. Collecting registers a listener and cancelling removes it; it
never starts or closes the cache, so you still own the lifecycle. See
[Flows](../coroutines/flows.md).

## Observability

With the [Micrometer module](../integrations/index.md) wired in, every snapshot load —
the initial prime, each `rebuild()`, and each compaction resync — reports an
`etcd.cache.sync` timer and an `etcd.cache.size` distribution summary. Cache paths never
become tags, because prefix cardinality is unbounded. See
[Observability](../observability.md).

A steadily climbing `etcd.cache.sync` count on an idle cache means something is resyncing
it repeatedly — usually compaction chasing a watch that keeps falling behind.
