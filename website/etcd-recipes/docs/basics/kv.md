# Key/value

etcd is a flat, ordered, byte-to-byte map with a revision attached to every write. Not a
tree, despite the slashes: `/services/worker-1` is a key whose name happens to contain
separators, and "children" are nothing more than a prefix range. Once that clicks, most of
this API stops needing explanation.

`KVUtils` is the layer over jetcd's `KV` client. It unwraps the `CompletableFuture`, applies
the retry policy, and marshals values so callers stop hand-rolling `ByteSequence.from(...)`.

## Putting and getting

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:put-get"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:put-get"
    ```

`getValue` comes in two shapes, and the difference matters. The single-argument form returns
a **nullable** `ByteSequence`, because "this key does not exist" is a real answer and not an
error. The forms taking a default collapse that into a value you choose — convenient, but
they also make an absent key indistinguishable from a key explicitly set to the default.
When that distinction carries meaning, take the nullable one.

!!! note "Puts are retried; that is safe here, but not everywhere"

    `putValue` retries on retriable statuses because these values are last-writer-wins: a
    duplicate apply from an ambiguous first attempt is harmless. This reasoning does **not**
    extend to compare-and-swap writes, which is why [`transaction { }`](txn.md) is never
    retried for you.

### Numbers are bytes, not text

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:put-typed"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:put-typed"
    ```

!!! warning "`putValue(key, 42)` does not write `"42"`"

    The `Int` and `Long` overloads write **fixed-width big-endian bytes** (4 and 8 of them),
    not decimal text. So a value written with the `Int` overload must be read with `asInt`,
    and one written as a `String` must be read with `asString`. Mixing them does not throw at
    the etcd boundary — it hands you a wrong number, or a `String` full of unprintable bytes.

    This is also why the fixed width matters: `asInt` on an 8-byte value, or `asLong` on a
    4-byte one, is a bug. If a value's type may change over its lifetime, encode it
    explicitly with a [codec](../typed-values.md) rather than relying on every reader
    remembering which overload the writer used.

## Deleting

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:delete"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:delete"
    ```

!!! warning "`deleteKeys` is a loop, not a transaction"

    Its name suggests a batch, but it issues one RPC per key. A failure halfway through
    leaves the earlier deletes applied and the rest of the keys alive. When the keys must
    vanish together, put `deleteOp`s in a [transaction](txn.md); when they are a subtree, use
    `deleteChildren`, which is a single ranged delete.

## Testing for existence

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:key-presence"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:key-presence"
    ```

Both are implemented as a one-shot transaction on the key's *version* rather than a GET, so
testing for a large value costs nothing extra.

!!! danger "Never branch on presence to decide whether a write will win"

    `isKeyPresent` answers "did this key exist at some recent revision?" — never "will it
    exist when I act on this". Another client may create or delete it between your check and
    your next line. This is the single most common way to write a race into an etcd program:

    ```kotlin
    // WRONG: two clients can both see false and both write.
    if (client.isKeyNotPresent("/locks/leader")) {
      client.putValue("/locks/leader", "node-1")
    }
    ```

    The check and the write must be one atomic step, which is what a
    [transaction](txn.md#create-if-absent) is for. Use `isKeyPresent` for logging, health
    output, and tests — not for control flow that races.

## Reading responses in full

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:get-response"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:get-response"
    ```

`getResponse` is the escape hatch to jetcd's whole `GetResponse`: the header revision (which
you need to anchor a [watch](watch.md)), the total count, and every matching `KeyValue`.
`getKeyValuePairs` is the same call with the response flattened to `(key, value)` pairs.

!!! note "`getResponse` retries a paging quirk for you"

    When etcd answers with no keys but `isMore` set, the extension re-issues the GET rather
    than handing back a misleading empty result — up to ten attempts, after which it throws
    `EtcdRecipeRuntimeException`. You will probably never see this; it is here so you do not
    have to wonder about it.

## Children

There are no directories in etcd. Every helper below appends a trailing `/` to the path and
does a prefix range read, which is what makes `/services` and `/services/` equivalent while
keeping `/servicesX` out of the results — a genuinely easy mistake to make by hand.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:children"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:children"
    ```

| Function | Returns |
| --- | --- |
| `getChildren` | `List<Pair<String, ByteSequence>>` — keys and values |
| `getChildrenKeys` | `List<String>` — keys only, and a cheaper read (`withKeysOnly`) |
| `getChildrenValues` | `List<ByteSequence>` — values only |
| `getChildCount` | `Long` — a count-only read; no values cross the wire |
| `getFirstChild` / `getLastChild` | `GetResponse` limited to one key |
| `deleteChildren` | `List<String>` — the keys it removed, from `prevKvs` |

`getChildren`, `getChildrenKeys`, and `getChildrenValues` all take a `SortTarget` and
`SortOrder`, defaulting to `KEY`/`ASCEND`. Sorting by `CREATE` is the interesting one: etcd
assigns create revisions server-side, so ordering by them is a cluster-wide agreement on who
arrived first. That single fact is what `getFirstChild(path, SortTarget.CREATE)` provides,
and it is the foundation the queue, election, and barrier recipes are built on.

!!! tip "`deleteChildren` is atomic; `deleteKeys` is not"

    `deleteChildren` compiles to one ranged delete RPC, so the subtree disappears at a single
    revision. Prefer it over fetching the keys and looping.

## Converting values

etcd stores bytes. These extensions are the entire marshalling story, and they are all in
`ByteSequenceUtils`, `KeyValueUtils`, `PairUtils`, and `PathUtils`.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:conversions"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:conversions"
    ```

| Extension | On | Gives |
| --- | --- | --- |
| `asByteSequence` | `String`, `Int`, `Long` | `ByteSequence` |
| `asString` / `asInt` / `asLong` | `ByteSequence` | the decoded value |
| `asPair` | `KeyValue` | `Pair<String, ByteSequence>` |
| `asString` / `asInt` / `asLong` | `KeyValue`, `Pair`, `List<Pair>` | decoded pairs |
| `keys` / `values` | `List<Pair<String, T>>` | `List<String>` / `List<T>` |
| `appendToPath` | `String` | a joined path with exactly one separator |

`appendToPath` looks trivial and is not: it strips a trailing separator from the receiver and
a leading one from the suffix, so `"/services/".appendToPath("/worker-1")` and
`"/services".appendToPath("worker-1")` both give `/services/worker-1`. Hand-rolled string
concatenation produces `//` about as often as not, and a double slash is a *different key* —
one that silently drops out of the prefix reads you expect to find it in.

## Compaction

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:compact"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:compact"
    ```

etcd keeps every historical revision until told otherwise, so compaction is how a long-lived
cluster's disk usage stays bounded. It is normally an operator's job (etcd can do it on a
timer), but the call is here when you need it.

!!! danger "Compaction kills watchers anchored below the compacted revision"

    A watch resuming from revision *N* cannot be served once *N* has been compacted away —
    etcd fails the stream with `CompactedException`, and the events in the gap are
    unrecoverable. Any derived state built from that stream is now wrong and cannot be
    repaired by replaying.

    This is not a hypothetical: it is the failure the resilient watcher's `resyncWith` hook
    exists to absorb, by re-reading the world and re-anchoring. See
    [Watches](watch.md#surviving-a-dead-stream).

## Typed values

The `ByteSequence` overloads leave marshalling to you, which gets old quickly and gets
dangerous when a writer and a reader disagree. `TypedKVUtils` takes an `EtcdCodec<T>` so the
encoding lives in one place:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/KvSnippets.kt:codec"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/KvSnippets.java:codec"
    ```

`getValue(key, codec)` returns `T?` — null for an absent key, exactly like the untyped form.
There is no default-taking overload; use `?: default`.

!!! note "Java must pass every argument here"

    `TypedKVExtensions.kt` does not carry `@JvmOverloads`, so Java callers supply
    `PutOption.DEFAULT` and `RpcResilience.DEFAULT` explicitly rather than getting the short
    form Kotlin's default parameters provide.

Built-in codecs are `StringCodec`, `ByteSequenceCodec`, and `jsonCodec<T>()` for anything
`@Serializable`. The full picture, including which recipes accept a codec, is on
[Typed values](../typed-values.md).
