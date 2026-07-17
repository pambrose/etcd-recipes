# Queues

etcd-recipes ships three queue recipes:

| Recipe | Use it when |
| --- | --- |
| [`DistributedQueue`](#distributedqueue) | FIFO by arrival |
| [`DistributedPriorityQueue`](#distributedpriorityqueue) | Order by priority, not arrival |
| [`DistributedWorkQueue`](#distributedworkqueue) | The item must survive the consumer processing it |

All three extend `EtcdConnector`, so they share the lifecycle, exception, and
connection-state surface described in [Core concepts](../getting-started/concepts.md).

!!! danger "The first two lose an item when a consumer dies"

    `DistributedQueue` and `DistributedPriorityQueue` **delete** the item as part of
    taking it. The moment `dequeue()` returns, etcd has no memory of the item — if
    your process dies on the next line, the work is simply gone. That is the queue's
    contract, not a bug (Curator's queues behave the same), and it is the right
    trade when items are cheap or replayable.

    When losing an item is not acceptable, use [`DistributedWorkQueue`](#distributedworkqueue).
    It claims instead of deletes, and redelivers what a dead consumer never finished.

## The take side

`DistributedQueue` and `DistributedPriorityQueue` differ only in how items are
*ordered*. Everything about taking them is shared, and lives in `AbstractQueue`:

```kotlin
abstract class AbstractQueue : EtcdConnector {
  fun dequeue(): ByteSequence
  fun tryDequeue(): ByteSequence?
  fun poll(timeout: Duration): ByteSequence?
  fun poll(timeout: Long, timeUnit: TimeUnit): ByteSequence?
  val size: Int
}
```

| Call | When the queue is empty |
| --- | --- |
| `dequeue()` | Blocks until an item arrives |
| `tryDequeue()` | Returns `null` immediately |
| `poll(timeout)` | Blocks up to `timeout`, then returns `null` |

Every take is a transaction that deletes the head key guarded on its mod revision,
so exactly one consumer wins each item no matter how many are racing. A loser does
not fail — it re-reads the new head and tries again. When the queue is empty the
take parks on a watcher rather than polling, and re-reads the head after waking:
the first PUT the watcher happens to see is not necessarily the head by sort order.

!!! note "`size` is an RPC, and advisory"

    etcd cannot push a count, so `size` issues a range-count on every read. It is
    true at some recent revision and may be stale by your next line — fine for
    logging and dashboards, useless for deciding whether a take will succeed. Use
    `tryDequeue`/`poll` for that; they are atomic. The same warning applies to
    Micrometer's `bindQueueDepth` gauge, which calls `size` on **every scrape**.

## `DistributedQueue`

FIFO by arrival, ordered by mod revision (`SortTarget.MOD`) — etcd's own commit
order, not a client clock.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/queue/QueueSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/queue/QueueSnippets.java:basic"
    ```

`enqueue` is overloaded for `String`, `Int`, `Long`, and `ByteSequence`. The take
side always hands back a `ByteSequence`; `asString` / `asInt` / `asLong` convert it
back (Java: the static `ByteSequenceUtils.getAsString(…)` and friends).

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/queue/QueueSnippets.kt:try-dequeue"
    ```

=== "Java"

    ```java
    --8<-- "java/website/queue/QueueSnippets.java:try-dequeue"
    ```

!!! tip "Kotlin takes `Duration`, Java takes `(long, TimeUnit)`"

    Both `poll` overloads exist. The `kotlin.time.Duration` one reads better from
    Kotlin; the `(long, TimeUnit)` one is the one Java can call at all — a Kotlin
    `Duration` parameter mangles the JVM method name, so `poll(Duration)` is not
    reachable from Java. Same story for `receive` on the work queue.

### Batch enqueue

`enqueueAll` writes every value in **one** transaction:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/queue/QueueSnippets.kt:enqueue-all"
    ```

=== "Java"

    ```java
    --8<-- "java/website/queue/QueueSnippets.java:enqueue-all"
    ```

All-or-nothing matters more than it looks. A loop of individual `enqueue` calls
that dies halfway leaves a partial batch that consumers are already draining, and
nothing tells them the rest is never coming. One transaction has no such state:
either every entry is visible at the same revision, or none is.

Batched entries share that revision, so mod-revision order cannot separate them.
Their keys embed the argument index instead, which is what keeps within-batch order
equal to argument order.

### Scoped usage

```kotlin
--8<-- "kotlin/website/queue/QueueSnippets.kt:scoped"
```

`withDistributedQueue` builds the queue, runs the block against it as receiver, and
closes it on the way out. Java has no equivalent — use try-with-resources, as the
tabs above do. See the [Java guide](../java.md).

## `DistributedPriorityQueue`

Ordered by priority instead of arrival: entries are filed under a zero-padded
priority prefix and taken in key order (`SortTarget.KEY`). **Lower number wins.**

```kotlin
--8<-- "kotlin/website/queue/PriorityQueueSnippets.kt:basic"
```

Note that `minimumWaitTime` has no default on the constructor — pass
`Duration.ZERO` when you do not want pacing. The scoped function does default it:

```kotlin
--8<-- "kotlin/website/queue/PriorityQueueSnippets.kt:scoped"
```

!!! warning "Java cannot construct `DistributedPriorityQueue`"

    `minimumWaitTime` is a `kotlin.time.Duration`, and it has no default — so every
    generated constructor carries a `Duration` parameter, and the Kotlin compiler
    marks constructors with inline-class parameters synthetic. There is no
    Java-visible constructor left. The `enqueue(value, priority: Int)` overloads are
    perfectly callable; it is only the construction that Java cannot express.
    Instantiate it from Kotlin, or use `DistributedQueue` / `DistributedWorkQueue`,
    both of which Java constructs normally.

### Priorities are `0..65535`

```kotlin
--8<-- "kotlin/website/queue/PriorityQueueSnippets.kt:priority-types"
```

Eight `enqueue` overloads cover four value types (`String`, `Int`, `Long`,
`ByteSequence`) times two priority types (`Int`, `UShort`). `UShort` is the real
one: priorities are stored as a five-digit key prefix. The `Int` overloads exist for
convenience and **range-check** their argument, throwing `IllegalArgumentException`
outside `0..65535` rather than letting `toUShort()` wrap silently — a wrapped
`70000` becomes `4464`, which is not an error anywhere, just an item that quietly
sorts into the wrong bucket.

### `minimumWaitTime`

```kotlin
--8<-- "kotlin/website/queue/PriorityQueueSnippets.kt:minimum-wait-time"
```

An enqueue at a given priority derives its sequence number from the current last
child at that priority, then commits under a CAS. Two producers computing the same
sequence number is ordinary contention: the loser re-reads and retries, up to 50
attempts before throwing `EtcdRecipeRuntimeException`. `minimumWaitTime` spaces out
writes from *this instance* to thin that contention. It is not a cluster-wide rate
limit, and it sleeps the calling thread.

!!! note "Concurrent consumers do not see a global order"

    A single consumer drains a priority queue in exact priority order. With several
    consumers, each take returns the head *as of that moment*, but the order in
    which items are observed across consumers is not a global ordering — one
    consumer may be slower to log than another that took a lower-priority item next.
    The guarantee is per-item: each is delivered to exactly one consumer.

## `DistributedWorkQueue`

The other two queues answer "who gets this item?". This one answers "who *finished*
this item?" — a much stronger question, and the reason this recipe exists.

A received item is not deleted. It moves to `claimed/`, and a separate claim marker
is written under the consumer's lease. The item is only removed when you `ack()` it.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/queue/WorkQueueSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/queue/WorkQueueSnippets.java:basic"
    ```

The payload is deliberately **not** bound to the consumer's lease; only the marker
is. So when a consumer dies, its markers evaporate with its lease while the payloads
survive, and any consumer's sweep moves the orphans back to the queue — under the
same key, so they return to their original FIFO position rather than the back.

`receive()` blocks, `receive(timeout)` bounds the wait, `tryReceive()` does not wait
at all. Each returns a `WorkItem`:

```kotlin
inner class WorkItem {
  val id: String
  val value: ByteSequence
  val attempt: Int
  fun ack(): Boolean
  fun requeue(): Boolean
}
```

### Configuration

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/queue/WorkQueueSnippets.kt:config"
    ```

=== "Java"

    ```java
    --8<-- "java/website/queue/WorkQueueSnippets.java:config"
    ```

!!! danger "`visibilityTimeoutSecs` bounds crash detection, not processing time"

    This is the single most misread knob on the recipe, because the name is
    borrowed from SQS, where it *does* bound processing.

    Here it is the TTL of the consumer's lease. A live consumer renews that lease in
    the background for as long as it is alive, so an item may legitimately take ten
    minutes to process under a 30-second visibility timeout without ever being
    redelivered. What the timeout actually bounds is how long the queue waits before
    concluding a *silent* consumer is dead and handing its claims to someone else.

    So tune it against how fast you want crash recovery, not against how slow your
    handler is. Lower means faster redelivery after a genuine crash, and less
    tolerance for a consumer that is merely partitioned.

### At-least-once means idempotent

```kotlin
--8<-- "kotlin/website/queue/WorkQueueSnippets.kt:consumer"
```

Delivery is at-least-once, and the duplicate is not hypothetical: a consumer
partitioned for longer than its visibility timeout keeps working on an item that has
already been redelivered elsewhere. Both consumers run the handler. Only one `ack()`
wins — the other returns `false`, because the ack is guarded on the claim still
belonging to this consumer.

Which is why `ack()` and `requeue()` return `Boolean` rather than `Unit`, and why
`false` deserves a log line: it does not mean the work failed, it means **the work
may have been done twice**. Your handler has to be safe under that regardless —
`false` arrives after the side effects, not before. Make the handler idempotent
(upsert on a key derived from `WorkItem.id`, or a dedupe table) and treat `false` as
a signal for your metrics rather than something to compensate for.

`requeue()` hands an item back immediately with its attempt count preserved — the
right move when a handler fails fast and you would rather not make every other
consumer wait out the visibility timeout for a crash that did not happen.

### Dead letters

An item that keeps failing cannot be redelivered forever. Once its attempts reach
`maxDeliveries`, the reclaim sweep routes it to the dead-letter space instead of
back to the queue:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/queue/WorkQueueSnippets.kt:dead-letters"
    ```

=== "Java"

    ```java
    --8<-- "java/website/queue/WorkQueueSnippets.java:dead-letters"
    ```

`deadLetters()` lists them as `DeadLetter(id, value, attempts)`. Nothing drains that
list automatically — that is the point. A dead letter is a poison pill that has
already burned `maxDeliveries` attempts across your fleet, and the decision of
whether it is a bug to fix (`requeueDeadLetter(id)`, which returns it with a fresh
attempt count) or garbage to drop (`purgeDeadLetter(id)`) is not one the library can
make. Alert on the list being non-empty.

### Delayed delivery

```kotlin
--8<-- "kotlin/website/queue/WorkQueueSnippets.kt:delayed"
```

An item enqueued with a delay is parked out of sight until it matures, then promoted
into the queue in ready-time order, interleaving correctly with items enqueued
immediately. A zero or negative delay enqueues immediately.

!!! note "Maturity is judged against client clocks"

    The ready time is stamped by the producer's clock and compared against the
    consumer's. Skew shifts delivery by the skew. At visibility-timeout scale
    (seconds) that is tolerable; do not build a scheduler that needs millisecond
    accuracy on top of it.

    The delayed `enqueue(value, delay)` overload takes a `kotlin.time.Duration`, so
    like `poll(Duration)` it is **not callable from Java**.

### The consumer lease

```kotlin
--8<-- "kotlin/website/queue/WorkQueueSnippets.kt:lease-listener"
```

Every claim this consumer makes hangs off a single self-healing lease, granted
lazily — a producer that only ever calls `enqueue` never takes a lease or starts a
sweeper thread.

The lease heals for *future* claims only. Markers made under a dead lease are
deliberately allowed to die with it, because that is exactly the visibility
contract: their items become reclaimable. Healing them would resurrect claims on
work that another consumer has already been handed. So a `LeaseEvent.Expired` means
your outstanding claims are gone even though healing will succeed. See
[Leases and loss](../resilience/leases.md).

Closing the queue revokes the lease, which makes any unacked claims reclaimable at
once rather than after the visibility timeout — a clean shutdown returns work to the
fleet immediately.

### Two gaps worth knowing

Unlike the other two queues, the work queue has:

- **no scoped function** — there is no `withDistributedWorkQueue`. Use `use { }` in
  Kotlin, try-with-resources in Java.
- **no typed variant** — there is no `TypedDistributedWorkQueue`. Marshal values
  through an `EtcdCodec` by hand: `queue.enqueue(codec.encode(value))` and
  `codec.decode(item.value)`.

Neither is a design statement; they are simply not built yet.

## Typed queues

`TypedDistributedQueue<T>` and `TypedDistributedPriorityQueue<T>` marshal values
through an `EtcdCodec<T>`, so callers hand over `T` instead of `ByteSequence`:

```kotlin
--8<-- "kotlin/website/queue/QueueSnippets.kt:typed"
```

```kotlin
--8<-- "kotlin/website/queue/PriorityQueueSnippets.kt:typed"
```

!!! note "The typed wrappers are decorators, not connectors"

    They implement `Closeable` — they do **not** extend `EtcdConnector`. So
    `exceptions`, `isHealthy()`, `connectionState`, and the listener registrations
    are not on the typed instance; they are on the queue it wraps, reachable through
    the `untyped` property. `close()` delegates, so `use { }` still does the right
    thing.

Codecs, including `jsonCodec<T>()`, are covered in [Typed values](../typed-values.md).

## Coroutines

The blocking take on a queue costs you a thread for its whole wait, which is a poor
trade when the wait is unbounded. The suspending twins release it:

```kotlin
--8<-- "kotlin/website/queue/QueueSnippets.kt:coroutines"
```

```kotlin
--8<-- "kotlin/website/queue/WorkQueueSnippets.kt:coroutines"
```

`awaitEnqueue`, `receive` (the suspending twin of `dequeue`), `awaitTryDequeue`,
`awaitReceive`, `awaitTryReceive`, `WorkItem.awaitAck`, and `WorkItem.awaitRequeue`
all exist. Cancelling a wait consumes nothing and leaves no orphan claim. See
[Coroutines](../coroutines/index.md).

## Observability

With the [Micrometer module](../integrations/index.md) wired in, `dequeue` and `poll`
record to the `etcd.queue` timer (tagged `op`), measuring call → item in hand, so the
timer includes the wait on an empty queue. Enqueues and the work queue's `receive`
are not instrumented today. Queue paths never become tags — that would blow up
cardinality.

`bindQueueDepth(queue)` binds a gauge to `size`, at one range-count RPC per scrape;
on a hot queue with frequent scrapes, that load is worth a thought. See
[Observability](../observability.md).
