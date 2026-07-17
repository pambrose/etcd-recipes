# Locks

etcd-recipes ships three mutual-exclusion recipes:

| Recipe | Use it when |
| --- | --- |
| [`DistributedMutex`](#distributedmutex) | One holder at a time |
| [`DistributedReadWriteLock`](#distributedreadwritelock) | Many readers, or one writer |
| [`DistributedSemaphore`](#distributedsemaphore) | At most *N* holders at a time |

All three extend `EtcdConnector`, so they share the lifecycle, exception, and
connection-state surface described in [Core concepts](../getting-started/concepts.md).

## The `EtcdLock` interface

`DistributedMutex` and the two views of `DistributedReadWriteLock` implement `EtcdLock`:

```kotlin
interface EtcdLock {
  fun lock()
  fun tryLock(timeout: Duration): Boolean
  fun tryLock(timeout: Long, timeUnit: TimeUnit): Boolean
  fun unlock(): Boolean
  val isHeldByCurrentThread: Boolean
  val isLocked: Boolean
  val holdCount: Int
  fun addLockLostListener(listener: LockLostListener)
  fun removeLockLostListener(listener: LockLostListener)
}
```

!!! note "Why not `java.util.concurrent.locks.Lock`?"

    `EtcdLock` deliberately does **not** implement `Lock`. Three of `Lock`'s promises
    cannot be honoured over a network: `newCondition()` has no distributed meaning,
    a no-arg `tryLock()` cannot be answered without a round trip, and — most
    importantly — `Lock` has no vocabulary for *losing* a lock you already hold.
    A distributed lock can evaporate underneath you when its lease expires. Pretending
    otherwise by implementing `Lock` would make that failure invisible.

## `DistributedMutex`

Built on etcd's native lock service, so ordering is server-side FIFO by revision.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/MutexSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/MutexSnippets.java:basic"
    ```

`withLock { }` is an inline Kotlin extension. It does compile to a static
`EtcdLockKt.withLock(lock, Function0)` that Java *can* call, but a Java lambda would have
to `return Unit.INSTANCE`, so it buys nothing — Java callers take the lock and release it
in a `finally`. See the [Java guide](../java.md).

### Acquiring with a timeout

Blocking forever is rarely what a service wants. `tryLock` bounds the wait:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/MutexSnippets.kt:try-lock"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/MutexSnippets.java:try-lock"
    ```

!!! tip "Kotlin takes `Duration`, Java takes `(long, TimeUnit)`"

    Both overloads exist on every lock. The `kotlin.time.Duration` one reads better
    from Kotlin; the `(long, TimeUnit)` one is the one to use from Java.

### Losing the lock

This is the part that has no single-JVM analogue, and it is the part worth reading twice.

A mutex hold is backed by a lease. If the process stalls or the network partitions
long enough for that lease to expire, **etcd has already handed the lock to the next
waiter**. The recipe does not try to heal that lease or reclaim the lock: doing so
would race the new holder and defeat the entire point of the lock.

Instead, loss is cooperative. You are told, and you decide:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/MutexSnippets.kt:lock-lost"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/MutexSnippets.java:lock-lost"
    ```

Three things happen on loss:

- every registered `LockLostListener` fires,
- `connectionState` moves to `LOST`,
- the eventual `unlock()` returns `false` rather than throwing.

If you would rather the holding thread be interrupted the instant the lock is lost —
so it cannot keep mutating state it no longer owns — opt in with `interruptOnLockLoss`:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/MutexSnippets.kt:interrupt-on-loss"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/MutexSnippets.java:interrupt-on-loss"
    ```

It defaults to `false` because interrupting a thread that is midway through
non-idempotent work is not automatically safer than letting it finish and fail its
own commit. Choose deliberately.

### Reentrancy

Holds are per-thread and reentrant, tracked by `holdCount`:

```kotlin
--8<-- "kotlin/website/locks/MutexSnippets.kt:reentrant"
```

!!! warning "A thread that dies holding the lock does not release it"

    The hold is released when `unlock()` is called or the recipe is closed — not when
    the acquiring thread dies. A thread that terminates inside the critical section
    leaves the lock held until `close()` or lease expiry. This matches Curator's
    behaviour. Always release in a `finally` (or use `withLock`).

## `DistributedReadWriteLock`

Many concurrent readers, or exactly one writer. Fair: waiters are served FIFO by
create revision, so a steady stream of readers cannot starve a waiting writer.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/ReadWriteLockSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/ReadWriteLockSnippets.java:basic"
    ```

`DistributedReadWriteLock` does not itself implement `EtcdLock` — it exposes two
properties that do, `readLock` and `writeLock`. Everything from the mutex section
(timeouts, lock-lost listeners, reentrancy) applies to each view independently.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/ReadWriteLockSnippets.kt:try-lock"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/ReadWriteLockSnippets.java:try-lock"
    ```

### Downgrade works, upgrade throws

Taking the read lock while holding the write lock (**downgrade**) is safe and
supported:

```kotlin
--8<-- "kotlin/website/locks/ReadWriteLockSnippets.kt:downgrade"
```

Taking the write lock while holding the read lock (**upgrade**) is not:

```kotlin
--8<-- "kotlin/website/locks/ReadWriteLockSnippets.kt:upgrade"
```

!!! danger "Upgrade throws instead of hanging"

    A read→write upgrade would make the write lock wait on a predecessor that the
    calling thread itself holds — a self-deadlock that no timeout can distinguish
    from ordinary contention. Rather than hang, the recipe throws
    `EtcdRecipeRuntimeException`. Release the read lock, then take the write lock,
    and re-validate whatever you read: another writer may have run in between.

## `DistributedSemaphore`

At most *N* holders across the cluster.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/SemaphoreSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/SemaphoreSnippets.java:basic"
    ```

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/SemaphoreSnippets.kt:try-acquire"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/SemaphoreSnippets.java:try-acquire"
    ```

### Semaphore holds are not lock holds

`DistributedSemaphore` deliberately does **not** implement `EtcdLock`, because its
holds follow `java.util.concurrent.Semaphore`'s rules rather than a lock's:

| | `EtcdLock` | `DistributedSemaphore` |
| --- | --- | --- |
| Ownership | The acquiring thread | The instance |
| Who may release | Only the holder | Any thread |
| Reentrant | Yes | **No** |
| Release order | — | LIFO |

```kotlin
--8<-- "kotlin/website/locks/SemaphoreSnippets.kt:multiple"
```

Permit loss mirrors lock loss — a listener, `connectionState` → `LOST`, `release()`
returning `false`, and an opt-in `interruptOnPermitLoss`:

```kotlin
--8<-- "kotlin/website/locks/SemaphoreSnippets.kt:permit-lost"
```

### The permit count is fixed by the first writer

The canonical count is CAS-created at `<semaphorePath>/permits` by whichever instance
reaches the path first. An instance that later names the same path with a different
count throws rather than silently reconfiguring the semaphore under everyone else:

```kotlin
--8<-- "kotlin/website/locks/SemaphoreSnippets.kt:mismatch"
```

!!! note "`availablePermits()` is advisory"

    So is `isLocked` on a lock. Both are true-at-some-recent-revision, and another
    client may act between your read and your next line. Use them for logging and
    dashboards; never branch on them to decide whether an acquire will succeed. Use
    `tryAcquire`/`tryLock` for that — they are atomic.

## Coroutines

Every lock has suspending twins that release the thread while waiting. Note the
asymmetry: `withLock` is **scoped-only** for locks (a lock is thread-owned, so the
suspending version pins a confined dispatcher and releases under `NonCancellable`),
while the semaphore also exposes split `awaitAcquire`/`awaitRelease` because its holds
are instance-level. See [Coroutines](../coroutines/index.md).

## Observability

With the [Micrometer module](../integrations/index.md) wired in, locks report
`etcd.lock.wait` (tagged `acquired`/`timeout`) and `etcd.lock.hold` timers. Lock paths
never become tags — that would blow up cardinality. See [Observability](../observability.md).
