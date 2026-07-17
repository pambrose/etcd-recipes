# etcd basics

The recipes in this library are layered on a thin Kotlin extension layer over
[jetcd](https://github.com/etcd-io/jetcd) — they are **not** a wrapper around it. That
distinction is the design, not a detail:

- Nothing is hidden. A `Client` is jetcd's `Client`, a `GetResponse` is jetcd's
  `GetResponse`. You never hold a proxy object, and you never have to escape the library
  to reach etcd's real API.
- The layer is additive. Everything in `io.etcd.recipes.common` is an extension function
  or property, so it composes with jetcd rather than shadowing it. Where jetcd is already
  pleasant, there is nothing here to learn.
- It earns its place at the edges: retry and timeout policy, `CompletableFuture` unwrapping,
  byte marshalling, and builder ergonomics. Those are the things every recipe would
  otherwise reimplement.

`common/` is where nearly all direct etcd interaction happens. A new recipe should compose
these extensions rather than reach into jetcd itself — that is what keeps resilience policy,
metrics, and error semantics consistent across every recipe instead of scattered through a
dozen of them.

## You may not need the recipes

These four pages are worth reading even if you only came for [the recipes](../recipes/index.md),
because every recipe is built out of exactly what is described here. A leader election is a
lease plus a transaction plus a watch. Knowing what those three do is what makes the recipes'
failure modes legible rather than mysterious.

They are also enough on their own. Plenty of coordination problems are one transaction, and
reaching for `DistributedMutex` when a CAS would do is the expensive way to solve them.

| Page | Covers |
| --- | --- |
| **[Key/value](kv.md)** | Puts, gets, deletes, children, and the byte-marshalling helpers |
| **[Watches](watch.md)** | Streaming change notifications, and the callback rule you cannot break |
| **[Transactions](txn.md)** | etcd's atomic if/then/else, and the basis of every CAS recipe |
| **[Leases](lease.md)** | TTLs, keep-alives, and the mechanism under every ephemeral key |

## Why every file has a `@JvmName`

Kotlin compiles top-level functions into a class named after the file — `KVExtensions.kt`
would become `KVExtensionsKt`. That is an unpleasant name to type from Java, and worse, it
is an implementation detail that would change if the file were ever renamed.

So every extension file declares one:

```kotlin
@file:JvmName("KVUtils")

package io.etcd.recipes.common
```

This is precisely what makes the layer usable from Java. `client.putValue("/key", "value")`
in Kotlin is `KVUtils.putValue(client, "/key", "value")` in Java — the receiver becomes the
first argument, and the facade class is a name that was chosen rather than derived:

| Source file | Java facade | Covers |
| --- | --- | --- |
| `ClientExtensions.kt` | `ClientUtils` | `connectToEtcd`, `ping`, recipe client defaults |
| `KVExtensions.kt` | `KVUtils` | `putValue`, `getValue`, `deleteKey`, `compact`, key presence |
| `TypedKVExtensions.kt` | `TypedKVUtils` | codec-based `putValue` / `getValue` |
| `ChildrenExtensions.kt` | `ChildrenUtils` | `getChildren`, `getChildCount`, `deleteChildren` |
| `WatchExtensions.kt` | `WatchUtils` | `watcher`, `withWatcher`, `watcherWithLatch` |
| `TxnExtensions.kt` | `TxnUtils` | `transaction`, comparisons, `setTo`, `deleteOp` |
| `LeaseExtensions.kt` | `LeaseUtils` | `leaseGrant`, `leaseRevoke`, `keepAlive` |
| `KeepAliveExtensions.kt` | `KeepAliveUtils` | `putValueWithKeepAlive`, `putValuesWithKeepAlive` |
| `LockExtensions.kt` | `LockUtils` | raw pass-through to etcd's lock service |
| `BuilderExtensions.kt` | `BuilderUtils` | `getOption { }`, `putOption { }`, `watchOption { }`, … |
| `ByteSequenceExtensions.kt` | `ByteSequenceUtils` | `asByteSequence`, `asString`, `asInt`, `asLong` |
| `KeyValueExtensions.kt` | `KeyValueUtils` | `KeyValue` → `Pair` conversions |
| `PairExtensions.kt` | `PairUtils` | `keys`, `values`, bulk pair decoding |
| `PathExtensions.kt` | `PathUtils` | `appendToPath` |

!!! tip "Extension properties become `get`-prefixed statics"

    An extension **property** has no argument list to become a first parameter, so Kotlin
    exposes it as a getter. `bytes.asString` in Kotlin is `ByteSequenceUtils.getAsString(bytes)`
    in Java, and `"/key".doesExist` is `TxnUtils.getDoesExist("/key")`. It reads oddly the
    first time and then never surprises you again. See the [Java guide](../java.md).

## Resilience is a parameter, not a mode

Almost every extension here takes a trailing `rpc: RpcResilience` argument that defaults to
`RpcResilience.DEFAULT` — a retry policy plus an operation timeout. You will see it in the
signatures and can safely ignore it until you need to change it; the snippets on these pages
never pass it, which is the common case.

Two deliberate exceptions are worth knowing up front, because they encode judgements you
would otherwise have to make yourself:

- **`transaction { }` is never retried.** A failed commit is ambiguous — it may have applied
  — so retrying it is a correctness decision that belongs to the caller, not the transport.
- **`lock` defaults to `RpcResilience.DISABLED`.** A lock call legitimately waits server-side
  for the current holder, and a 30-second operation timeout would abort valid waits.

The full story, including watch and lease recovery, is on [Resilience](../resilience/index.md).

## Where to go next

If you have not connected yet, start with [Connecting](../getting-started/connecting.md).
If you want the recipe-level lifecycle and exception model, read
[Core concepts](../getting-started/concepts.md).
