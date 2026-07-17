# Coroutines

Every recipe in etcd-recipes has a blocking API, and every blocking call that waits
on a network round trip has a suspending twin. The twins do not reimplement anything:
they are a thin bridge over the same recipes, so a coroutine and a thread contending
for the same lock are the same client to etcd.

!!! note "This page is Kotlin-only"

    The coroutines layer has no Java equivalent — it is built on `suspend` functions
    and `Flow`, neither of which Java can call. Java callers use the blocking API
    described in [Recipes](../recipes/index.md); see the [Java guide](../java.md) for
    the idioms (`try`/`finally` in place of `withLock`, listeners in place of flows).

## The naming convention

A blocking method `foo()` gets a suspending twin `awaitFoo()`, taking the same
arguments and returning the same type:

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:kv"
```

Two families deliberately break the pattern, because `awaitWaitOnBarrier()` and
`awaitDequeue()` read worse than the alternative:

| Blocking | Suspending |
| --- | --- |
| `DistributedBarrier.waitOnBarrier()` | `DistributedBarrier.await()` |
| `DistributedBarrierWithCount.waitOnBarrier()` | `DistributedBarrierWithCount.await()` |
| `AbstractQueue.dequeue()` | `AbstractQueue.receive()` |
| `AbstractQueue.poll(timeout)` | `AbstractQueue.receive(timeout)` |

Everything else is `await` + the blocking name.

## The surface, by area

### Lifecycle — `LifecycleSuspend.kt`

Starting a recipe is itself a set of round trips, so it suspends too.

| Suspending | Blocking twin |
| --- | --- |
| `TransientKeyValue.awaitStart()` | `start()` |
| `DistributedAtomicLong.awaitStart()` | `start()` |
| `PathChildrenCache.awaitStart(buildInitial)` / `awaitStart(mode)` | `start(...)` |
| `PathChildrenCache.awaitStartComplete()` / `awaitStartComplete(timeout)` | `waitOnStartComplete(...)` |
| `LeaderSelector.awaitStart()` | `start()` |
| `LeaderSelector.awaitLeadershipComplete()` / `(timeout)` | `waitOnLeadershipComplete(...)` |
| `LeaderSelector.awaitFinished()` / `(timeout)` | `waitUntilFinished(...)` |

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:lifecycle"
```

### Barriers — `BarrierSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `DistributedBarrier.awaitSetBarrier()` | `setBarrier()` |
| `DistributedBarrier.awaitRemoveBarrier()` | `removeBarrier()` |
| `DistributedBarrier.await()` / `await(timeout)` | `waitOnBarrier(...)` |
| `DistributedBarrierWithCount.await()` / `await(timeout)` | `waitOnBarrier(...)` |
| `DistributedDoubleBarrier.awaitEnter()` / `awaitEnter(timeout)` | `enter(...)` |
| `DistributedDoubleBarrier.awaitLeave()` / `awaitLeave(timeout)` | `leave(...)` |

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:barrier"
```

See [Barriers](../recipes/barriers.md).

### Counters — `CounterSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `DistributedAtomicLong.awaitGet()` | `get()` |
| `DistributedAtomicLong.awaitIncrement()` / `awaitDecrement()` | `increment()` / `decrement()` |
| `DistributedAtomicLong.awaitAdd(value)` / `awaitSubtract(value)` | `add(value)` / `subtract(value)` |

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:counter"
```

### Queues — `QueueSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `AbstractQueue.receive()` | `dequeue()` |
| `AbstractQueue.receive(timeout)` | `poll(timeout)` |
| `AbstractQueue.awaitTryDequeue()` | `tryDequeue()` |
| `DistributedQueue.awaitEnqueue(value)` | `enqueue(value)` |
| `DistributedQueue.awaitEnqueueAll(values)` | `enqueueAll(values)` |
| `DistributedPriorityQueue.awaitEnqueue(value, priority)` | `enqueue(value, priority)` |

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:queue"
```

`awaitEnqueue` is overloaded for `ByteSequence`, `String`, `Int`, and `Long`, matching
the blocking API; the priority-queue overloads take either a `UShort` or an `Int`
priority. See [Queues](../recipes/queues.md), and [Typed values](../typed-values.md)
for the codec-backed variants.

### Work queues — `WorkQueueSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `DistributedWorkQueue.awaitEnqueue(value)` / `awaitEnqueue(value, delay)` | `enqueue(...)` |
| `DistributedWorkQueue.awaitEnqueueAll(values)` | `enqueueAll(values)` |
| `DistributedWorkQueue.awaitReceive()` / `awaitReceive(timeout)` | `receive(...)` |
| `DistributedWorkQueue.awaitTryReceive()` | `tryReceive()` |
| `WorkItem.awaitAck()` | `ack()` |
| `WorkItem.awaitRequeue()` | `requeue()` |

### Service discovery — `DiscoverySuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `ServiceDiscovery.awaitRegisterService(service)` | `registerService(service)` |
| `ServiceDiscovery.awaitUpdateService(service)` | `updateService(service)` |
| `ServiceDiscovery.awaitUnregisterService(service)` | `unregisterService(service)` |
| `ServiceDiscovery.awaitQueryForNames()` | `queryForNames()` |
| `ServiceDiscovery.awaitQueryForInstances(name)` | `queryForInstances(name)` |
| `ServiceDiscovery.awaitQueryForInstance(name, id)` | `queryForInstance(name, id)` |
| `ServiceCache.awaitStart()` | `start()` |

### Key/value — `KVSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `Client.awaitPutValue(keyName, keyval, option, rpc)` | `putValue(...)` |
| `Client.awaitGetValue(keyName, rpc)` / with a `String`/`Int`/`Long` default | `getValue(...)` |
| `Client.awaitGetResponse(keyName, option, rpc)` | `getResponse(...)` |
| `Client.awaitGetKeyValuePairs(keyName, getOption, rpc)` | `getKeyValuePairs(...)` |
| `Client.awaitDeleteKey(keyName, rpc)` / `awaitDeleteKeys(vararg keyNames)` | `deleteKey(...)` / `deleteKeys(...)` |
| `Client.awaitIsKeyPresent(keyName, rpc)` / `awaitIsKeyNotPresent(...)` | `isKeyPresent(...)` / `isKeyNotPresent(...)` |
| `Client.awaitCompact(revision, option, rpc)` | `compact(...)` |

### Children — `ChildrenSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `Client.awaitGetChildren(keyName, target, order, keysOnly, rpc)` | `getChildren(...)` |
| `Client.awaitGetChildrenKeys(...)` / `awaitGetChildrenValues(...)` | `getChildrenKeys(...)` / `getChildrenValues(...)` |
| `Client.awaitGetFirstChild(keyName, target, rpc)` / `awaitGetLastChild(...)` | `getFirstChild(...)` / `getLastChild(...)` |
| `Client.awaitGetChildCount(keyName, rpc)` | `getChildCount(...)` |
| `Client.awaitDeleteChildren(keyName, rpc)` | `deleteChildren(...)` |

### Transactions — `TxnSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `Client.awaitTransaction(rpc) { }` | `transaction(rpc) { }` |

Like its blocking twin, `awaitTransaction` applies the operation timeout but is
**never retried**. A failed commit is ambiguous — it may already have been applied —
so re-issuing it is the caller's decision, not the library's. See
[Transactions](../basics/txn.md).

### Leases — `LeaseSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `Client.awaitLeaseGrant(ttl, rpc)` | `leaseGrant(ttl, rpc)` |
| `Client.awaitLeaseRevoke(lease, rpc)` | `leaseRevoke(lease, rpc)` |

`awaitLeaseRevoke` is best-effort: it runs on cleanup paths where a secondary failure
would mask the original problem, so failures are logged and swallowed and the TTL
bounds how long the lease can outlive you. Cancellation still propagates.

### Raw locks — `LockSuspend.kt`

| Suspending | Blocking twin |
| --- | --- |
| `Client.awaitLock(keyName, leaseId, rpc)` | `lock(keyName, leaseId, rpc)` |
| `Client.awaitUnlock(keyName, rpc)` | `unlock(keyName, rpc)` |

These are pass-throughs to etcd's lock service. Prefer
[`DistributedMutex`](../recipes/locks.md), which manages the lease, the reentrancy,
and the lock-lost notification for you.

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:raw-lock"
```

!!! warning "`awaitLock` defaults to `RpcResilience.DISABLED`"

    Every other suspending twin defaults to `RpcResilience.DEFAULT` — four attempts,
    a 30-second per-attempt deadline. `awaitLock` is the one exception, and the
    reason is that a lock call is *supposed* to take a long time: it waits
    server-side until the current holder releases. A 30-second operation timeout
    would abort perfectly healthy waits and turn ordinary contention into a stream of
    spurious failures.

    Pass a bounded `RpcResilience` only when a bounded wait is genuinely what you
    mean — and remember that a lock acquisition that times out client-side may still
    have been granted server-side.

### Locks and semaphores — `LockRecipesSuspend.kt`

Covered in the next two sections.

## Cancellation

This is the part worth reading properly, because it is where the coroutine layer earns
its keep.

The recipe twins — everything built on a blocking recipe rather than a raw jetcd call
— run their blocking body through an internal bridge (`Bridges.kt`) on
`Dispatchers.IO`:

```kotlin
internal suspend fun <T> interruptibleOn(dispatcher: CoroutineDispatcher, block: () -> T): T
internal suspend fun <T> etcdInterruptible(block: () -> T): T   // interruptibleOn(Dispatchers.IO)
```

Three things follow, and they are the whole contract:

**1. Cancellation interrupts the worker thread.** `runInterruptible` means a cancelled
coroutine does not merely stop waiting for the result — it interrupts the thread that
is actually blocked in the recipe. Nothing is left spinning in the background.

**2. The recipe's cleanup still runs, and runs to completion.** The blocking recipes
already handle interruption: a `finally` block revokes the acquisition lease, deletes
the queue entry, or removes the barrier waiter. `runInterruptible` does not abandon
the thread — it waits for that cleanup to finish before your `catch` sees anything.
So a cancelled `DistributedBarrierWithCount.await()` removes its waiter and stops
counting toward the barrier; a cancelled `queue.receive()` consumes nothing; a
cancelled `withLock` acquisition leaves nothing queued behind a lock it will never
take. **Cancellation is safe, not merely fast.**

**3. The failure reaches you as `CancellationException`.** The blocking RPC engine
catches a mid-call `InterruptedException` and rethrows it wrapped in an
`EtcdRecipeRuntimeException` — sometimes wrapped twice, because it re-sets the
interrupt flag first so a subsequent RPC on the same thread can wrap it again.
`runInterruptible` cannot recognise either as cancellation. The bridge therefore walks
the entire cause chain, and since a thread inside `runInterruptible` is interrupted
*only* by coroutine cancellation, an `InterruptedException` anywhere in that chain
unambiguously means "you were cancelled" — and is re-surfaced as
`CancellationException`.

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:cancellation-surfaces"
```

!!! tip "Catch `CancellationException`, not `EtcdRecipeRuntimeException`"

    On a shutdown path, an etcd exception that is really "we cancelled you" would be
    indistinguishable from a genuine etcd failure — and would light up your error
    dashboards on every clean shutdown. The bridge exists so that never happens.
    Structured concurrency's normal rules apply: catch it only to log, then rethrow.

!!! note "Why not an `isActive` check?"

    The obvious implementation — catch the exception, ask whether the `Job` is
    cancelled, and translate if so — is racy. The interrupt can be delivered a hair
    before the `Job`'s state flips to cancelled, which lets a "failure" escape from
    what was really a cancellation. Inspecting the cause chain has no such window.

Composing with `withTimeout` / `withTimeoutOrNull` works exactly as you would expect,
and is usually nicer than the bounded overloads when you want one deadline over
several calls:

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:cancellation-timeout"
```

!!! note "The raw extension twins don't use a thread at all"

    `KVSuspend`, `ChildrenSuspend`, `TxnSuspend`, `LeaseSuspend`, and `LockSuspend`
    wrap jetcd's `CompletableFuture` API, not a blocking recipe, so they never park a
    thread: they `await()` the future directly, applying `RpcResilience` with `delay`
    instead of `Thread.sleep` for backoff. Cancelling one cancels the in-flight
    future and propagates immediately — external cancellation is never retried. The
    observable contract is the same; only the cost differs. See
    [Resilience](../resilience/index.md).

## Locks are scoped-only; semaphores are not

`EtcdLock` gets exactly one suspending surface — `withLock` — while
`DistributedSemaphore` gets a split `awaitAcquire` / `awaitRelease` pair *and*
`withPermit`. That asymmetry is not an oversight; it falls straight out of what the
two recipes mean.

**An `EtcdLock` hold is owned by the acquiring thread.** `lock()` and `unlock()` must
run on the same thread, or `unlock()` throws `IllegalMonitorStateException`. A
coroutine has no such guarantee: it can resume on a different thread of
`Dispatchers.IO` after every suspension point. A raw suspending `lock()`/`unlock()`
pair would therefore be a loaded gun — correct in testing, and broken in production
the first time the dispatcher hands the continuation to another thread.

So `withLock` does the confining for you: it spins up one dedicated single-threaded
dispatcher per call, acquires and releases on that thread, and runs your body in the
caller's coroutine, where it may suspend freely.

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:with-lock"
```

The release leg runs under `NonCancellable`, so cancelling the body still releases the
lock rather than leaking the hold until lease expiry. There is a bounded variant, and
a `null` return always means "not acquired" — nothing was left queued:

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:with-lock-timeout"
```

!!! danger "The suspending `withLock` is not reentrant"

    The blocking `withLock` is reentrant, tracked by `holdCount`. The suspending one
    is not: each call is an independent acquisition on a *fresh* thread, so nesting
    `withLock` on the same lock self-deadlocks — the inner acquisition waits for a
    hold the outer one will not release until the inner one returns. This matches
    kotlinx's `Mutex`, and it is the price of confinement. The blocking API's
    write→read downgrade does not carry across calls either.

**A `DistributedSemaphore` permit is owned by the instance, not by a thread.** Any
thread may release a permit that another acquired — the recipe follows
`java.util.concurrent.Semaphore`'s rules, not a lock's. Dispatcher hopping is
therefore harmless, and the split surface is safe:

| Suspending | Blocking twin |
| --- | --- |
| `DistributedSemaphore.awaitAcquire()` | `acquire()` |
| `DistributedSemaphore.awaitTryAcquire(timeout)` | `tryAcquire(timeout)` |
| `DistributedSemaphore.awaitRelease()` | `release()` |
| `DistributedSemaphore.awaitAvailablePermits()` | `availablePermits()` |
| `DistributedSemaphore.withPermit { }` | — |

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:semaphore-split"
```

`withPermit` is still the form to reach for, for the same reason `use` beats a manual
`close()`:

```kotlin
--8<-- "kotlin/website/coroutines/SuspendSnippets.kt:with-permit"
```

!!! warning "Star-importing both packages breaks `withLock` at compile time"

    `io.etcd.recipes.lock` and `io.etcd.recipes.coroutines` each declare a
    `withLock` extension on `EtcdLock` — one inline and blocking, one suspending.
    A file that star-imports both gets an **overload-resolution ambiguity** on every
    `withLock { }` call, and the compiler cannot pick for you: the lambdas differ only
    in whether they are `suspend`.

    ```kotlin
    import io.etcd.recipes.lock.*        // ✗ brings in the blocking withLock
    import io.etcd.recipes.coroutines.*  // ✗ brings in the suspending withLock
    ```

    Import the one you mean explicitly:

    ```kotlin
    import io.etcd.recipes.lock.DistributedMutex
    import io.etcd.recipes.coroutines.withLock   // ✓ unambiguous
    ```

    The same applies to `withPermit`. This is a deliberate trade: the alternative was
    a second name (`withLockSuspending`) that would have been uglier on every call
    site in exchange for a nuisance the compiler catches immediately.

## Flows

Everything above is request/response. Watches, cache events, leadership hand-offs,
lease transitions, and connection state are push, and they get `Flow` surfaces
instead — see [Flows](flows.md).
