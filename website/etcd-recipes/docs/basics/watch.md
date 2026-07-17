# Watches

A watch is a server-pushed stream of changes to a key or a key range, anchored at a revision.
It is the only way to learn about a change without polling, and it is the mechanism every
reactive recipe — caches, barriers, election, service discovery — is built from.

`WatchUtils` wraps jetcd's watch client with three things it does not give you: a Kotlin
lambda instead of a `Watch.Listener`, a scoped `withWatcher` that cannot leak the watch, and
recovery from the stream deaths jetcd abandons.

## The rule that comes before everything else

!!! danger "Never block a watch callback on anything that needs etcd"

    jetcd 0.7+ delivers watch callbacks on its **Vert.x gRPC event loop**. Anything a callback
    does that needs another gRPC response — a GET, a put, a transaction — or that contends for
    a lock held by a caller who is *itself* waiting on a gRPC response, deadlocks that event
    loop. Not "runs slowly": stops, permanently, and takes every other watch on the client
    down with it.

    The `Client.watcher` extension defends against the direct form of this by hopping every
    callback onto a **dedicated single-thread executor**, so a callback that calls etcd is no
    longer instantly fatal. That is a real guarantee and you should rely on it.

    It is not a licence to stop thinking. Two hazards survive it:

    - **The lock-ordering deadlock is still yours to avoid.** If your callback takes a lock
      that a caller holds while waiting on a gRPC response, nothing above saves you. The
      response cannot arrive until the caller's lock is released; the caller cannot proceed
      until your callback returns.
    - **The dispatcher is one thread, not a pool.** Until a callback returns, no further event
      for that watcher is delivered. A slow callback is a stalled watch and a growing lag
      behind reality.

    Treat the callback as a handoff: decode the event, hand it to a thread you own, return.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/WatchSnippets.kt:callback-offload"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/WatchSnippets.java:callback-offload"
    ```

An exception thrown from your block is caught and logged rather than allowed to kill the
dispatcher — a watch does not die because one event handler had a bad day. But it is also not
reported anywhere you are likely to be looking, so do not rely on throwing to signal anything.

## Watching a key

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/WatchSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/WatchSnippets.java:basic"
    ```

`watcher` returns a `Watch.Watcher`, which is `Closeable`. It is a live gRPC stream and a live
thread; if you drop the reference without closing it, you have leaked both.

The `WatchEvent` extensions — `keyAsString`, `keyAsInt`, `keyAsLong`, `valueAsString`,
`valueAsInt`, `valueAsLong` — save a trip through `event.keyValue.key` on every event.

!!! warning "A DELETE event carries no value"

    `event.valueAsString` on a `DELETE` gives you an empty string, not the value that was
    removed, because etcd does not send it by default. If you need the prior value, ask for it
    with `withPrevKV(true)` on the `WatchOption` and read `event.prevKV`.

### Scoping a watch to a block

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/WatchSnippets.kt:with-watcher"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/WatchSnippets.java:with-watcher"
    ```

`withWatcher` opens the watch, runs your receiver block with the watcher as the receiver, and
closes it on exit — including on an exception. Prefer it to `watcher` plus a hand-written
`finally`.

Its close is not a fire-and-forget: it cancels any pending recovery attempt, shuts the
dispatcher down, and then waits up to **five seconds** for a callback that is currently running
to finish. That wait is what makes "the watcher is closed, so nothing is touching my state
any more" true rather than merely likely. If a callback outruns the wait, you get a warning
rather than a silent lie.

## Watching a prefix

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/WatchSnippets.kt:watch-prefix"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/WatchSnippets.java:watch-prefix"
    ```

`watchOption { }` is the `BuilderUtils` builder for jetcd's `WatchOption`; `isPrefix(true)` is
the whole difference between watching one key and watching a range. This is one stream for the
entire subtree, not one per key — which is why the recipes watch a prefix rather than tracking
membership and opening a watch per member.

Other options worth knowing:

| Option | Effect |
| --- | --- |
| `withRevision(n)` | Start at revision *n* rather than "now" — the key to gap-free reads |
| `withPrevKV(true)` | Include the pre-change value on each event |
| `withNoPut(true)` / `withNoDelete(true)` | Filter one event type out server-side |
| `withProgressNotify(true)` | Periodic empty responses so a quiet watch keeps its revision fresh |
| `withRequireLeader(true)` | Fail the stream rather than serve from a partitioned member |

!!! tip "GET first, then watch from `revision + 1`"

    The standard way to build a cache without a gap or a duplicate: GET the range, note
    `header.revision` from that response, then start the watch at `revision + 1`. Watching
    first and then reading races in the other direction. This is exactly what
    `resyncWith` does after a compaction, and what `PathChildrenCache` does at startup.

### Put and delete as separate callbacks

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/WatchSnippets.kt:watcher-with-latch"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/WatchSnippets.java:watcher-with-latch"
    ```

`watcherWithLatch` splits the event stream into `onPut` and `onDelete` and **blocks the calling
thread** on the latch until you count it down from elsewhere. It is a convenience for the
"watch until told to stop" shape; `UNRECOGNIZED` event types are ignored rather than surfaced.

## Surviving a dead stream

jetcd already retries *transient* stream errors itself — a dropped connection, an etcd restart
— and does it with revision continuity, so you lose nothing. Those are not the interesting
failures.

What jetcd **abandons** are *fatal* deaths:

- the watched revision was **compacted** away,
- the server returned a **halt-error** status,
- `etcdserver: no leader`.

Before 0.12 a fatally dead watch was simply dropped: no error, no events, no notification. Your
cache would sit there looking healthy and slowly diverge from reality. The `watcher` overload
taking a `WatchResilience` is the fix.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/WatchSnippets.kt:resilient"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/WatchSnippets.java:resilient"
    ```

Recovery re-subscribes from the revision just past the last observed event, so **no event is
lost or duplicated across a recovery** — with one exception, which is the whole reason
`resyncWith` exists.

### `resyncWith` and the compaction gap

Compaction is different in kind from the other failures. When the revision you were anchored at
has been compacted away, the events between it and the present are *gone*. There is no stream
to resume, and no amount of retrying invents the missing history. State derived from those
events is now wrong and cannot be repaired by replaying.

So `resyncWith` is invoked — only after a compaction, on the dispatcher thread — to let you do
the only thing that can work: read the current state directly and start again from there. It
returns the new anchor revision, typically your GET's `header.revision + 1`.

Its contract:

- **Called only on compaction.** Ordinary recoveries never invoke it.
- **Returns the revision to re-anchor at.** Nothing else uses the return value.
- **Runs on the dispatcher thread**, so a blocking GET inside it is expected and correct.
  It cannot deadlock the event loop, which is precisely why the hop exists.
- **If you omit it**, the watch resumes just past the compacted revision — the gap is not
  filled, only reported, via `WatchRecoveryEvent.Resynced`.

!!! warning "If you maintain derived state, supply `resyncWith`"

    Without it, a compaction leaves your cache, membership set, or counter permanently missing
    whatever happened in the gap. The watch keeps running and keeps looking healthy. That is a
    worse failure than an error, because nothing tells you to go and look.

    If you keep no derived state — say the callback only logs, or only enqueues onto a queue
    whose consumer re-reads anyway — omitting it is fine.

### Recovery events

Every transition is reported to the optional `WatchRecoveryListener`:

| Event | Meaning |
| --- | --- |
| `Suspended(key, cause)` | The stream errored; recovery may follow |
| `Resubscribed(key, resumeRevision)` | A replacement watch is live, no events lost |
| `Resynced(key, compactRevision, anchorRevision)` | Compaction — events between the two are **gone** |
| `Failed(key, cause)` | The retry policy is exhausted; the watcher is **dead** |

`Failed` deserves an alert. Nothing more will be retried, and nothing further will arrive.

`WatchResilience` carries the `RetryPolicy` (exponential backoff by default) and
`progressNotify`, which is on by default so a quiet key's resume revision does not go stale.
`WatchResilience.DISABLED` restores the pre-0.12 behaviour of silently dropping a dead watch —
it exists for tests and for callers who genuinely want that, and it is not a good default for
anything running unattended. See [Resilience](../resilience/index.md).

!!! note "`ResilientWatcher` is internal"

    The recovering watcher is a private implementation detail, reachable only through the
    `watcher` and `withWatcher` factories. What you hold is a plain `Watch.Watcher`, so the
    recovery machinery can change without breaking you — and so there is no second, subtly
    different way to construct one.

## Watches in the recipes

Every reactive recipe here is a watch plus bookkeeping. `PathChildrenCache` is a prefix watch
over a subtree with a GET at startup; `LeaderSelector` watches the predecessor key rather than
polling for the crown; `DistributedBarrier` watches for the barrier key's deletion. If a recipe
already does what you need, use it — it has already made the resync and lifecycle decisions
described here.

For a coroutine-native view of the same streams, see [Flows](../coroutines/index.md).
