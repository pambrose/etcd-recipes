# Counters

`DistributedAtomicLong` is a single `Long` in etcd that any number of clients can safely
mutate. It is the distributed answer to `java.util.concurrent.atomic.AtomicLong` — with
one honest difference that runs through this whole page: every operation is a network
round trip, and the value is contended by machines you cannot see.

It extends `EtcdConnector`, so it shares the lifecycle, exception, and connection-state
surface described in [Core concepts](../getting-started/concepts.md).

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/counter/CounterSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/counter/CounterSnippets.java:basic"
    ```

## `start()` is optional

Unusually for this library, you do not have to call `start()`. It is invoked automatically
on the first call that needs the counter to exist, and calling it explicitly is also fine.

What `start()` does is create the key with `default` if it is absent — a single
`If(doesNotExist) Then(setTo default)` transaction, so concurrent first-users cannot
double-create it. The reason it is lazy is that the constructor used to do this work,
which made a `DistributedAtomicLong` impossible to construct without a live etcd. Deferring
it keeps construction I/O-free without changing what existing call sites see.

Call it explicitly when you want that round trip to happen at a point you chose — during
startup, say — rather than inside whatever your first `increment()` turns out to be.

## The default value belongs to whoever creates the key

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/counter/CounterSnippets.kt:default"
    ```

=== "Java"

    ```java
    --8<-- "java/website/counter/CounterSnippets.java:default"
    ```

`default` is only written by whichever client wins the create. Every instance that finds
the counter already present ignores its own `default` entirely. Two clients naming the same
path with different defaults do not conflict, do not throw, and do not reconfigure anything
— the first one to arrive simply decides, and the other never learns it disagreed.

## Mutating

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/counter/CounterSnippets.kt:mutators"
    ```

=== "Java"

    ```java
    --8<-- "java/website/counter/CounterSnippets.java:mutators"
    ```

`increment()`, `decrement()`, `add(n)`, and `subtract(n)` all return a `Long`, and *which*
`Long` is the most important thing on this page.

!!! note "Mutators return the value this call committed"

    Not a fresh read. The recipe reads the counter, computes `value + delta`, and commits
    it with a compare-on-`modRevision` transaction; on success it hands back the number it
    just wrote.

    It deliberately does **not** re-read the key afterwards. An earlier version did, and
    under contention another client could mutate the counter between the commit and the
    re-read — so callers were handed a value they had not committed, which is exactly the
    guarantee an atomic counter exists to provide. If ten clients each `increment()` a
    counter from 0, the ten return values are 1 through 10 in some order, every value
    distinct. That property is what lets you use `increment()` to hand out unique IDs.

    The corollary: the returned value is *not* "the counter's current value". It was, for
    an instant, at the revision your transaction committed at. By the time you read it,
    it may not be.

`get()` is a plain read with no such guarantee: true at some recent revision, possibly
stale on the next line. Use it for logging and dashboards. Never branch on it to decide
whether a mutation will succeed — that is what the mutators' atomicity is for.

## How the optimistic concurrency works

There is no lock. Each mutation is:

1. `GET` the counter, keeping its `modRevision`.
2. Compute the new value locally.
3. `Txn`: **if** `modRevision` is still what step 1 saw, **then** write the new value.
4. If the transaction succeeded, return the value written. If not, another client wrote
   first — back off and start over from step 1.

This is compare-and-swap over the network, and it has the same shape as
`AtomicLong.incrementAndGet()`'s retry loop. The difference is the cost of a lost race:
a CAS loss in a JVM costs nanoseconds, and a CAS loss here costs a round trip plus a
backoff sleep.

!!! warning "A hot counter is a contention point, not a throughput device"

    The retry loop is unbounded, and its backoff is a random sleep from a window that
    widens with each failed attempt. Under low contention that is invisible. Under high
    contention — many clients hammering one path
    — throughput collapses: every client is serialized through one etcd key, and each
    loser pays a full round trip plus a sleep before trying again. There is no fairness
    and no queue, so an unlucky client can lose repeatedly while others make progress.

    A single `DistributedAtomicLong` is a coordination primitive, not a metrics counter.
    If you are counting requests, count them locally and flush periodically. If you need
    per-shard counts, use per-shard paths — *N* uncontended counters cost the same as one
    uncontended counter and scale linearly; one counter shared by *N* clients does not.

Note also that a mutation is two round trips, not one: the `GET` and the `Txn`. That is
the price of returning the value you committed.

## Deleting

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/counter/CounterSnippets.kt:delete"
    ```

=== "Java"

    ```java
    --8<-- "java/website/counter/CounterSnippets.java:delete"
    ```

`delete` is `@JvmStatic` and takes a `Client` and a path. It is deliberately not an
instance method: removing the key out from under live instances is a destructive
administrative act, not a step in a counter's lifecycle. The next instance to touch the
path will re-create it with *its* `default`.

Closing a `DistributedAtomicLong` does not delete anything. The counter is durable etcd
state with no lease behind it; it outlives every process that ever touched it, until
someone deletes it.

## Scoped use

```kotlin
--8<-- "kotlin/website/counter/CounterSnippets.kt:scoped"
```

!!! note "`withDistributedAtomicLong` does not expose `resilience`"

    Its parameters are `(client, counterPath, default, receiver)`. Construct
    `DistributedAtomicLong` directly when you need a non-default `ResilienceConfig`:

    ```kotlin
    --8<-- "kotlin/website/counter/CounterSnippets.kt:resilience"
    ```

    Note what resilience does and does not cover here. It governs the individual RPCs —
    retries and timeouts on the `GET` and the `Txn`. The CAS retry loop is separate and
    always on; you cannot turn it off, because a lost CAS is not a failure, it is the
    algorithm working. See [Resilience](../resilience/index.md).

## Java

`DistributedAtomicLong` is one of the friendliest recipes from Java: no generics, no
codecs, no lambdas required. The only thing to watch is that Java has no named arguments,
so reaching `default` means passing it positionally. See the [Java guide](../java.md).
