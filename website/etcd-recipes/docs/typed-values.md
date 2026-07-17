# Typed values

etcd stores bytes. jetcd surfaces those bytes as `ByteSequence`, and the base recipes do
the same — `DistributedQueue.dequeue()` hands you a `ByteSequence` and leaves the
marshalling to you.

That is the right default for a library (it cannot know your wire format), but it is the
wrong default for an application, where every call site ends up repeating the same
encode/decode dance. A **codec** moves that decision to one place: you name the format
once, and the recipe traffics in your type.

## The `EtcdCodec` interface

Two functions. That is the whole contract:

```kotlin
interface EtcdCodec<T> {
  fun encode(value: T): ByteSequence
  fun decode(bytes: ByteSequence): T
}
```

Nothing in the library knows or cares which implementation it holds, which is what lets
the [Jackson module](integrations/jackson.md) plug in without core depending on Jackson.

## Built-in codecs

| Codec | Payload | Notes |
| --- | --- | --- |
| `ByteSequenceCodec` | `ByteSequence` | The identity codec — nothing is marshalled |
| `StringCodec` | `String` | UTF-8 text |
| `KotlinxJsonCodec<T>(serializer, json)` | any `@Serializable` | Prefer the `jsonCodec<T>()` factory |

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:built-in"
```

`jsonCodec<T>(json = Json)` is an inline reified factory, so it resolves the serializer at
the call site and you never name it yourself. Construct `KotlinxJsonCodec` directly only
when you already hold a `KSerializer` or a customized `Json`.

The `Order` used throughout this page is an ordinary `@Serializable` data class:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:serializable"
```

## Typed key/value

The two typed KV extensions are the smallest thing a codec buys you — `putValue` and
`getValue` with the hand-marshalling removed:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:typed-kv"
```

They compose the raw `ByteSequence` overloads described in [Key/value](basics/kv.md), so
they inherit the same retry semantics. `getValue` returns `null` for an absent key rather
than throwing — use `?: default` for a fallback.

!!! note "Java sees these as `TypedKVUtils`"

    The file carries `@JvmName("TypedKVUtils")`, so from Java they are statics with the
    client as the first argument. They have no `@JvmOverloads`, so Java callers must pass
    the trailing `PutOption` / `RpcResilience` defaults explicitly. See
    [Jackson](integrations/jackson.md) for a worked example.

## Typed recipes

Five recipes take a codec:

| Typed | Wraps | Covered in |
| --- | --- | --- |
| `TypedDistributedQueue<T>` | `DistributedQueue` | [Queues](recipes/queues.md) |
| `TypedDistributedPriorityQueue<T>` | `DistributedPriorityQueue` | [Queues](recipes/queues.md) |
| `TypedPathChildrenCache<T>` | `PathChildrenCache` | [Caches](recipes/caches.md) |
| `TypedTransientKeyValue<T>` | `TransientKeyValue` | [Transient key/values](recipes/keyvalue.md) |
| `NodeCache<T>` | — (not a decorator) | [Caches](recipes/caches.md) |

Each mirrors the API of the recipe it wraps, with `T` where a `ByteSequence` used to be:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:typed-queue"
```

## `untyped` is the escape hatch

This is the one structural thing worth understanding about the typed layer, because it
explains an asymmetry you will hit immediately.

**The four decorators are `Closeable`, not `EtcdConnector`.** They wrap a recipe rather
than extending it, so they do *not* inherit the connector surface described in
[Core concepts](getting-started/concepts.md) — no `exceptions`, no `isHealthy()`, no
`connectionState`, no background-exception or connection-state listeners. What they expose
instead is a single public property:

```kotlin
val untyped: DistributedQueue
```

That is the wrapped instance itself, and it is the documented, supported way to reach the
full connector API:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:untyped"
```

!!! note "Why decorate instead of subclass?"

    A `TypedDistributedQueue<Order>` **is not** a `DistributedQueue` — its `dequeue()`
    returns an `Order`, not a `ByteSequence`. Making it a subclass would mean either
    overloading every method into an ambiguous mess or lying about the type. Composition
    keeps both APIs honest and total: the typed methods are typed, and everything else is
    one `.untyped` away rather than half-reimplemented on the wrapper.

Only the pieces that genuinely change shape are re-exposed on the wrapper — `close()`,
`start()`, the listeners whose event types become typed. Everything else, deliberately,
is not.

### `NodeCache` is the exception

`NodeCache<T>` takes a codec but **is** an `EtcdConnector`. It is not a decorator: there
is no untyped `NodeCache` to wrap, because caching a single key was a typed idea from the
start. So its connector API sits directly on the instance, with no `untyped` hop:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:node-cache"
```

If you find yourself reaching for `.untyped` on a `NodeCache`, that is why it isn't there.

## Typed caches

`TypedPathChildrenCache<T>` decodes every child value, so `currentData` yields
`TypedChildData<T>` and listeners receive decoded events:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:typed-cache"
```

!!! warning "A malformed payload is recorded, not thrown, on the watch path"

    The read accessors (`currentData`, `getCurrentData`, `currentDataAsMap`, and
    `NodeCache.current`) decode lazily and will throw at *your* call site if a value is
    malformed. But a decode failure on an incoming *event* cannot be thrown at anyone —
    it happens on the watch dispatcher. Those are recorded on `untyped.exceptions` (on
    `exceptions` for `NodeCache`) and the event is skipped, so a single bad write does not
    kill the cache. Check the exception sink; do not assume silence means health.

## Typed transient key/values

`TypedTransientKeyValue<T>` encodes its (immutable) value once and publishes it under a
lease:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:typed-transient"
```

!!! warning "Text codecs only"

    `TransientKeyValue` publishes a `String`, so the typed wrapper requires a codec whose
    output is valid UTF-8 text — `StringCodec`, `jsonCodec`, or the JSON `JacksonCodec`. A
    binary codec (protobuf, a packed struct) will not survive the round trip through
    `String`. The same restriction applies to service payloads, below, and for the same
    reason.

## Typed service payloads

A `ServiceInstance` carries an opaque `jsonPayload` string. The typed helpers layer over
it, leaving the instance's own wire format byte-for-byte unchanged:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:typed-service"
```

`payload(codec)` decodes, `setPayload(value, codec)` encodes in place, and the
`serviceInstance(name, payload, codec)` builder does it at construction. Because
`jsonPayload` is a `String`, these need a **UTF-8 text codec** — see
[Service discovery](recipes/discovery.md).

## Writing your own codec

There is nothing privileged about the built-ins. If your payload is a protobuf message, a
packed binary struct, or a bare `Long`, implement the interface:

```kotlin
--8<-- "kotlin/website/codecs/CodecSnippets.kt:custom-codec"
```

A codec is expected to be thread-safe and side-effect free: recipes call `decode` from
watch dispatcher threads and `encode` from whichever thread enqueues. The built-ins are
`object`s or hold an immutable `Json` for exactly that reason.

## Java and Jackson

`jsonCodec<T>()` is inline and reified, and `kotlinx-serialization` wants its compiler
plugin — neither travels well to Java. Java callers should use the
[Jackson module](integrations/jackson.md) instead: `JacksonCodec<T>` implements this same
`EtcdCodec<T>`, takes a `Class` token or a `TypeReference`, and drops into every typed
recipe on this page unchanged.
