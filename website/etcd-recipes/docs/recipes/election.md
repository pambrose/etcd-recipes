# Leader election

Exactly one node in a cluster should run the nightly report, drive the scheduler, or
compact the shard map. etcd-recipes ships three recipes for deciding which one:

| Recipe | Use it when |
| --- | --- |
| [`LeaderSelector`](#leaderselector) | Leadership is a *job*: take it, do the work, hand it on |
| [`LeaderLatch`](#leaderlatch) | Leadership is a *role*: hold it for the process lifetime |
| [`LeaderObserver`](#leaderobserver) | You only need to *know* who leads, without competing |

All three extend `EtcdConnector`, so they share the lifecycle, exception, and
connection-state surface described in [Core concepts](../getting-started/concepts.md).
They also share one etcd key scheme, so a latch, a selector, and an observer pointed at
the same election path interoperate: the latch is literally built out of selectors.

| | `LeaderSelector` | `LeaderLatch` | `LeaderObserver` |
| --- | --- | --- | --- |
| Runs for election | Yes | Yes | **No** |
| Leadership lives | Inside `takeLeadership` | From `await()` until `close()` | — |
| Term ends when | The callback returns | `close()`, or lease loss | — |
| `start()` reusable | **Yes**, many terms | No — one-shot | No — one-shot |
| Ask "am I leader?" | `isLeader` | `hasLeadership` | `currentLeader` |
| Callback | `LeaderSelectorListener` | `LeaderLatchListener` | `LeaderListener` |

!!! note "One leader means one leader *that etcd knows about*"

    Every recipe here elects a leader by CAS-creating a single `LEADER` key under the
    election path, holding it with a lease. That gives you mutual exclusion in etcd's
    view of the world — which is not the same as mutual exclusion in wall-clock time.
    A leader that is partitioned away is still executing your code for up to a lease
    TTL after etcd has already promoted its successor. Leader election tells you who
    *should* act; it cannot stop a stalled process from acting. If the work must never
    double-execute, fence it with the leader's own writes (a CAS on a term or revision
    number), not with the election alone.

## `LeaderSelector`

Leadership lives *inside a callback*. You are handed the leadership, you do the work,
and the moment `takeLeadership` returns, the term is over and the lease is revoked so a
successor can win immediately.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderSelectorSnippets.java:basic"
    ```

`start()` returns as soon as the leader watch is live and this node is in the running —
it does **not** block until you win. The blocking call is `waitOnLeadershipComplete()`,
which returns once this node has led and finished its term, or once `close()` ends its
candidacy. A node that never wins stays parked there, which is the point: it is standing
by to take over.

There are two constructors and they are the same constructor. The Kotlin one takes the
callbacks as lambdas (`takeLeadershipBlock` / `relinquishLeadershipBlock`) and wraps them
in a listener for you; the Java-facing one takes the `LeaderSelectorListener` directly.
Kotlin can use either — the listener form is the better fit when the callbacks are large
enough to want a name:

```kotlin
--8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:listener"
```

`LeaderSelectorListenerAdapter` implements both methods as no-ops, so you override only
the transition you care about:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:adapter"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderSelectorSnippets.java:adapter"
    ```

!!! tip "`relinquishLeadership` always runs"

    It runs on a clean hand-off, on a step-down after lease loss, **and** when
    `takeLeadership` throws. Whatever the leader acquired — a scheduler, a partition
    assignment, an open file — release it there rather than in a `finally` inside
    `takeLeadership`, and it is released on every exit path.

### Holding leadership instead of finishing it

Returning from `takeLeadership` is how you *give up* leadership. A node that wants to
lead until it shuts down must therefore park inside the callback:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:hold"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderSelectorSnippets.java:hold"
    ```

!!! warning "Park on `waitUntilFinished()`, not `waitOnLeadershipComplete()`"

    The two look interchangeable and are not. `waitOnLeadershipComplete()` is for the
    *caller* — it checks that `start()` was called and that `close()` was not, and it
    waits on the start thread. Calling it from inside `takeLeadership` throws once
    `close()` runs. `waitUntilFinished()` takes no instance monitor and makes no
    lifecycle assertions, so a `close()` on another thread releases it cleanly. That is
    why it exists.

If you need to hold leadership but would rather not think about parking at all, that is
exactly what [`LeaderLatch`](#leaderlatch) is.

### One instance, many terms

A `LeaderSelector` is reusable: once a term has completed, `start()` may be called
again. This is the shape for a worker that should keep re-entering the election rather
than dropping out after one turn.

```kotlin
--8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:reuse"
```

Calling `start()` while a previous term is still in flight throws
`EtcdRecipeRuntimeException("Previous call to start() not complete")` rather than
quietly running two overlapping terms from one instance.

### Who else is running?

Every candidate advertises itself under `<electionPath>/participants` with a
self-healing lease, so a node that survives a partition longer than its TTL reappears in
the list instead of silently vanishing:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:participants"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderSelectorSnippets.java:participants"
    ```

`Participant` is a `clientId` and an `isLeader` flag.

!!! note "`getParticipants` is advisory"

    It is a static read of two key ranges at some recent revision, not an atomic
    snapshot: the leader can change between the read of `LEADER` and the read of the
    participants. Use it for dashboards and log lines. Never branch control flow on it —
    `isLeader` on your own selector is the only answer that is about *you*, and even
    that is a point-in-time read.

### Scoped functions

`withLeaderSelector` builds the selector, runs your block against it, and closes it on
exit. Unlike the latch and observer equivalents it does **not** call `start()` — running
for election stays an explicit act.

```kotlin
--8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:scoped"
```

It takes a lambda with receiver, so it has no natural Java equivalent: Java callers
construct the selector in a try-with-resources, as every Java tab above does. See the
[Java guide](../java.md).

## `LeaderLatch`

The Curator-style shape: acquire leadership and **hold it until `close()`**. No callback
owns the leadership; your ordinary code does.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderLatchSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderLatchSnippets.java:basic"
    ```

Internally the latch runs a worker thread that composes a fresh `LeaderSelector` per
term — which is why latches and selectors can contest the same election path. When a
term ends by step-down rather than by `close()`, the latch loops back and re-contests
with a new selector.

### Awaiting leadership

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderLatchSnippets.kt:timed-await"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderLatchSnippets.java:timed-await"
    ```

!!! danger "`close()` does not unblock a parked `await()`"

    The no-arg `await()` waits on the leadership monitor, and that monitor is only ever
    flipped *true* by actually gaining leadership. A follower parked in `await()` is
    released only by an interrupt or by the timeout — **closing the latch will not wake
    it**, and a shutdown path that closes the latch and then joins the awaiting thread
    deadlocks. Prefer `await(timeout)`; reach for the no-arg form only when the thread
    genuinely has nothing else to do and something else will interrupt it.

`hasLeadership` answers the question without blocking:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderLatchSnippets.kt:has-leadership"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderLatchSnippets.java:has-leadership"
    ```

### Listeners

`LeaderLatchListener` has two no-op-defaulted methods, dispatched on the latch's own
notification thread in `isLeader` → `notLeader` order:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderLatchSnippets.kt:listener"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderLatchSnippets.java:listener"
    ```

Callbacks are queued onto a single notification thread, so they are serialized — but a
listener that blocks for long delays every subsequent transition, including the
`notLeader` that tells the rest of your system to stop.

!!! warning "`start()` and `close()` are one-shot each"

    This is the sharpest difference from `LeaderSelector`, which is reusable across
    terms. A second `start()` on a latch throws
    `EtcdRecipeRuntimeException("start() already called")`, and `close()` is terminal —
    a closed latch cannot re-enter the election. That is deliberate: a latch models a
    *role* held for the lifetime of a process, and re-contesting after step-down is
    already handled internally. If you want an instance that repeatedly runs for
    election and yields, use a `LeaderSelector`.

`withLeaderLatch` closes the latch on exit and — unlike `withLeaderSelector` — starts it
for you:

```kotlin
--8<-- "kotlin/website/election/LeaderLatchSnippets.kt:scoped"
```

## `LeaderObserver`

Watch an election without competing in it. A router that needs to forward writes to the
current leader, or a dashboard that shows who holds the role, has no business being a
candidate:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderObserverSnippets.kt:basic"
    ```

`currentLeader` is the leader's `clientId`, or `null` when the election is vacant.
`LeaderObserver` is the blocking, Java-friendly counterpart to the coroutine
[`Client.leadershipAsFlow`](../coroutines/flows.md).

Hand-offs are reported to a `LeaderListener`:

```kotlin
--8<-- "kotlin/website/election/LeaderObserverSnippets.kt:listener"
```

!!! note "`start()` seeds `currentLeader` but emits nothing"

    `start()` reads the leader key once so that `currentLeader` is already correct when
    it returns — but it deliberately fires **no** synthetic `takeLeadership` for the
    leader it found. Only *changes* trigger callbacks. So a listener registered before
    `start()` will not hear about the leader that was already in place; read
    `currentLeader` for the starting state and let the listener handle everything after.
    (The flow API makes the opposite choice and emits the current leader first, because
    a `Flow` has no separate "read the snapshot" surface.)

`LeaderListener.onError` defaults to a no-op and is invoked when one of your callbacks
throws, or when the underlying watch is abandoned. The watch loop keeps running after a
callback throws — it is your failure, not the observer's.

`withLeaderObserver` starts the observer and closes it on exit:

```kotlin
--8<-- "kotlin/website/election/LeaderObserverSnippets.kt:scoped"
```

!!! tip "Observers survive etcd restarts and compaction"

    The observer is backed by the resilient watcher, so a hand-off missed while the
    stream was dead is recovered by re-reading the leader key after resubscribe or
    resync — you get a late callback rather than a permanently stale `currentLeader`.
    See [Resilience](../resilience/index.md).

## Losing the lease

This is the part with no single-JVM analogue.

Leadership is backed by a lease. If the process stalls or the network partitions long
enough for that lease to expire, **etcd has already deleted the leader key, and another
candidate may already be leading**. The recipe therefore does not heal the leadership
lease — re-granting it would race the new leader and produce exactly the split brain the
election exists to prevent. Instead the leader *steps down*:

- `isLeader` / `hasLeadership` turns `false` immediately,
- `waitUntilFinished()` and `waitOnLeadershipComplete()` are released,
- `relinquishLeadership()` (or `LeaderLatchListener.notLeader()`) fires,
- the cause is recorded in `exceptions` and `connectionState` moves to `LOST`,
- with `interruptOnLeaseLoss` (the default), the `takeLeadership` thread is interrupted.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderSelectorSnippets.kt:lease-loss"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderSelectorSnippets.java:lease-loss"
    ```

The latch handles the same event one level up: it ends the term, reports `notLeader()`,
and then **re-contests** with a fresh selector rather than assuming it still leads. So a
latch that survives a partition may find itself leading again — or may find a successor
in place and quietly become a follower. Either way `hasLeadership` is the truth.

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/election/LeaderLatchSnippets.kt:lease-loss"
    ```

=== "Java"

    ```java
    --8<-- "java/website/election/LeaderLatchSnippets.java:lease-loss"
    ```

!!! danger "`interruptOnLeaseLoss` defaults to `true` — and `interruptOnLockLoss` defaults to `false`"

    The asymmetry is deliberate, and it follows from what the two things protect.

    A [lock](locks.md) holder that loses its lease is midway through a critical section
    it chose to enter. Interrupting it may leave non-idempotent work half-done, and the
    lock recipe has no idea whether finishing-and-failing-the-commit is safer than
    stopping dead. So it tells you and lets you decide: `interruptOnLockLoss` is opt-in.

    A leader that loses its lease is different. It is not midway through one bounded
    section — it is *parked*, typically in `waitUntilFinished()`, a `sleep`, or a poll
    loop, and everything it does from here on is done under an authority it no longer
    has. Worse, a successor is very likely already running the same code. Leaving that
    thread alive to keep leading is the split-brain case. So the default is to interrupt
    it and end the term promptly.

    Set `interruptOnLeaseLoss = false` when your `takeLeadership` block is already
    structured to notice — it polls `isLeader`, or it only ever parks on
    `waitUntilFinished()`, which is released by the step-down without needing an
    interrupt. Do not set it to `false` merely because an interrupt is inconvenient.

!!! note "Participation leases *are* healed; leadership leases are not"

    The two leases in an election have opposite failure semantics. Losing your
    *participation* key just makes you invisible in `getParticipants()`, which is
    harmless to heal — so it is healed automatically. Losing your *leadership* lease is
    a statement by etcd that you are no longer the leader. There is nothing safe to heal
    there. See [Leases and loss](../resilience/leases.md).

## Coroutines

`Client.leadershipAsFlow(electionPath)` observes an election as a `Flow` of
`LeadershipEvent` — `Elected`, `Vacated`, `WatchFailed` — emitting the current leader
first so a late collector is not blind:

```kotlin
--8<-- "kotlin/website/election/LeaderObserverSnippets.kt:flow"
```

This is an observer, not a candidate. To *run* for election from coroutine code, use
`LeaderSelector` and its suspending twins — `awaitStart()`, `awaitLeadershipComplete()`,
and `awaitFinished()` — which release the thread instead of parking it. Note that
`takeLeadership` itself is still a blocking callback invoked on the selector's executor:
the coroutine surface covers waiting for a term, not the term. See
[Coroutines](../coroutines/index.md) and [Flows](../coroutines/flows.md).

## Observability

With the [Micrometer module](../integrations/index.md) wired in, every election reports
`etcd.election.transitions`, a counter tagged `transition=acquired` / `relinquished`.
A leader that flaps shows up as both counts climbing together — the signature of a lease
TTL that is too short for your network. `LeaderLatch` can additionally be registered as
an `etcd.election.leader` gauge (1 while this instance leads), which makes "exactly one
leader" an alertable invariant across the fleet. Election paths never become tags. See
[Observability](../observability.md).
