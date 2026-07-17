# Barriers

A barrier makes clients wait for something other than a lock. etcd-recipes ships three,
and they answer three different questions:

| Recipe | Use it when |
| --- | --- |
| [`DistributedBarrier`](#distributedbarrier) | One client decides when everyone else may proceed |
| [`DistributedBarrierWithCount`](#distributedbarrierwithcount) | Nobody proceeds until *N* parties have arrived |
| [`DistributedDoubleBarrier`](#distributeddoublebarrier) | *N* parties start together **and** finish together |

The first is a gate: someone holds it shut, everyone else queues, and one `removeBarrier()`
releases the lot. The second is a rendezvous with no gatekeeper — the last arrival releases
everyone, including itself. The third is the second one twice, wrapped around a phase of
work.

`DistributedBarrier` and `DistributedBarrierWithCount` extend `EtcdConnector` and share the
lifecycle, exception, and connection-state surface described in
[Core concepts](../getting-started/concepts.md). `DistributedDoubleBarrier` does not — see
[below](#distributeddoublebarrier).

## `DistributedBarrier`

One client arms the barrier, does whatever the others must not race, and removes it:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:set"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:set"
    ```

`setBarrier()` returning `false` is not a failure — it means another client got there
first and the barrier is already armed by somebody else. That distinction matters: the
client that armed it is the one that should remove it. `removeBarrier()` returns `false`
if this instance already removed it.

Everyone else waits:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:wait"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:wait"
    ```

`waitOnBarrier()` blocks until the barrier key is deleted, watching for the DELETE rather
than polling. The bounded overloads return `false` on timeout:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:wait-timeout"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:wait-timeout"
    ```

!!! tip "Kotlin takes `Duration`, Java takes `(long, TimeUnit)`"

    Both overloads exist on every barrier, alongside the no-arg form that waits
    indefinitely. The `kotlin.time.Duration` one reads better from Kotlin; the
    `(long, TimeUnit)` one is the one to use from Java.

A waiter holds no state between calls, so timing out and waiting again is free — that is
exactly what a "wait, log progress, wait again" loop does.

### Waiting on a barrier nobody set

By default (`waitOnMissingBarriers = true`) a waiter blocks on a barrier that does not
exist yet, on the theory that it is about to. That is what you want when waiters may start
before the coordinator does — arriving early should not mean sailing straight through.

Set it to `false` and a missing barrier reads as "already lifted", so `waitOnBarrier()`
returns `true` immediately:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:missing"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:missing"
    ```

`isBarrierSet()` is advisory — true at some recent revision, and possibly false by the time
you read the answer. Use it for logging; never branch on it to decide whether to wait, because
`waitOnBarrier()` already handles the "it lifted while I was asking" race and your `if` does
not.

### The barrier lease heals, and that is a trade-off

The barrier key is bound to a lease, and unlike a [lock's lease](locks.md) that one is
**self-healing**. The reasoning is the same as for [service registration](discovery.md):
nobody else is competing to own your barrier key, so re-creating it after an expiry does not
race anyone.

But a healed barrier is not the same as a barrier that never lapsed, and the difference is
visible to waiters:

1. the lease expires (a partition longer than the TTL),
2. **etcd deletes the key — every waiter sees a DELETE and lifts**,
3. the healer grants a new lease and re-arms the barrier for future waiters,
4. the expiry is recorded on `exceptions` and drives `connectionState`.

Step 2 is a spurious lift, and it is unavoidable: by the time this client can react, the key
is already gone and the waiters have already been released. No amount of healing can un-ring
that bell. What healing buys is that the barrier is armed again afterwards, so a waiter
arriving at step 3 blocks correctly instead of walking through a barrier everyone believes
is up.

```kotlin
--8<-- "kotlin/website/barrier/BarrierSnippets.kt:lease-loss"
```

!!! danger "Do not use a barrier as a lock"

    If a spurious lift would let two clients into the same critical section, you do not want
    a barrier — you want a [`DistributedMutex`](locks.md), whose lease deliberately does not
    heal and whose loss is reported to the holder. A barrier's contract is "wait until told";
    a lock's is "nobody else is in here". Only the second one is safe under an expiry.

    Size `leaseTtlSecs` against your worst tolerable partition, and treat the
    `LeaseEvent.Expired` recorded on `exceptions` as the signal that waiters may have been
    released early.

!!! note "`DistributedBarrier` has no `addLeaseListener`"

    [`ServiceRegistry`](discovery.md#serviceregistry), `TransientKeyValue`, and
    `DistributedWorkQueue` expose their healing lifecycle as typed `LeaseEvent`s. The
    barriers do not: their lease trouble surfaces as recorded exceptions (via
    `exceptions` / `addBackgroundExceptionListener`) and as `connectionState` transitions.
    See [Leases and loss](../resilience/leases.md).

The scoped form closes the barrier on exit:

```kotlin
--8<-- "kotlin/website/barrier/BarrierSnippets.kt:scoped"
```

## `DistributedBarrierWithCount`

No gatekeeper: every party calls the same `waitOnBarrier()`, and the barrier trips when
`memberCount` of them are parked on it.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:with-count"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:with-count"
    ```

Each waiter registers a lease-bound key under `<path>/waiting`, and the first arrival
CAS-creates `<path>/ready`. Every waiter watches the prefix; when the waiter count reaches
`memberCount`, `<path>/ready` is deleted and everyone leaves together. `waiterCount` is a
live count of the registered waiters — advisory, like every count read over a network, and
useful mostly for logging a stuck rendezvous.

`waitOnBarrier` throws `InterruptedException` and `EtcdRecipeException` — the latter when
the waiter's own key cannot be established, which means somebody else is already using that
exact token and this wait can never be counted.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:with-count-timeout"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:with-count-timeout"
    ```

!!! note "Leaving on any exit path"

    A timeout, an interrupt, or a cancelled coroutine all remove the waiting key on the way
    out, so a member that gave up stops counting toward the barrier instead of lingering as
    a phantom participant that can never arrive. `close()` also unblocks a thread parked in
    `waitOnBarrier()` rather than leaving it there forever — a wait cancelled that way
    returns `false`, the same as a timeout.

    The waiting key's lease self-heals while a member is parked, so a partition shorter than
    the caller's patience does not silently drop that member out of the count.

```kotlin
--8<-- "kotlin/website/barrier/BarrierSnippets.kt:with-count-scoped"
```

## `DistributedDoubleBarrier`

The rendezvous problem, twice. `enter()` blocks until all `memberCount` members have
arrived; `leave()` blocks until all of them have finished. Between the two is a phase in
which every member knows exactly who else is running.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:double"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:double"
    ```

It is a thin composition: two `DistributedBarrierWithCount` instances at `<path>/enter` and
`<path>/leave`. `enterWaiterCount` and `leaveWaiterCount` are those barriers' `waiterCount`s.
Everything the count barrier does — lease-bound waiting keys, self-healing while parked,
cleanup on every exit path — it does here too.

The `leave()` half is the one people skip, and it is the one that makes the recipe worth
using. Without it, the fastest member tears down its half of a shared fixture while the
slowest is still reading from it.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/barrier/BarrierSnippets.kt:double-timeout"
    ```

=== "Java"

    ```java
    --8<-- "java/website/barrier/BarrierSnippets.java:double-timeout"
    ```

!!! warning "`DistributedDoubleBarrier` is only a `Closeable`"

    Unlike every other recipe on this page, it does **not** extend `EtcdConnector` — so it
    has no `exceptions`, no `connectionState`, no `addConnectionStateListener`, no `ping()`.
    It also takes no `resilience` parameter (nor a `leaseTtlSecs`): its two inner barriers
    are built with defaults, and there is no way to pass a `ResilienceConfig` through.

    If you need either, compose two `DistributedBarrierWithCount` instances yourself at
    `<path>/enter` and `<path>/leave` and configure each — that is all this class does.

## Choosing one

- **Gate.** A migration must finish before any worker starts consuming. One client arms a
  `DistributedBarrier` at startup and removes it when the migration commits. Workers wait.
  The party count is unknown and irrelevant; what matters is that one party decides.
- **Rendezvous.** A sharded job where every shard must have loaded its slice before any
  shard emits, because the output is only consistent across the whole set.
  `DistributedBarrierWithCount(path, shardCount)`, one `waitOnBarrier()` per shard.
- **Phase sync.** The same sharded job, but the slices are also torn down at the end and
  nobody may tear down early. `DistributedDoubleBarrier`: `enter()`, run, `leave()`.

If you find yourself reaching for a barrier to protect a critical section, you want
[locks](locks.md) instead.

## Coroutines

Every wait has a suspending twin that releases the thread while parked, and that unblocks on
cancellation: `DistributedBarrier.await()`, `awaitSetBarrier()`, `awaitRemoveBarrier()`,
`DistributedBarrierWithCount.await()`, and `DistributedDoubleBarrier.awaitEnter()` /
`awaitLeave()` — each with the `Duration`-bounded overload.

```kotlin
--8<-- "kotlin/website/barrier/BarrierSnippets.kt:coroutines"
```

Cancelling a coroutine parked in one of these removes the waiting key on the way out, so a
cancelled member stops counting toward a `DistributedBarrierWithCount` immediately. See
[Coroutines](../coroutines/index.md).

## Observability

Barrier waits are watch-driven, so what shows up with the
[Micrometer module](../integrations/index.md) wired in is the watch's own health — the
`etcd.watch.recovery` counter — plus the connection-state transitions each recipe reports.
Barrier paths never become tags; that would blow up cardinality. See
[Observability](../observability.md).
