# Resilience

etcd is a network service, and every recipe in this library is a distributed
coordination primitive layered on top of it. Networks partition, etcd restarts, leaders
change. The question a recipe has to answer is not *whether* the connection breaks but
what it does when it does.

Resilience is **on by default**. Every recipe ends with the same trailing parameter:

```kotlin
resilience: ResilienceConfig = ResilienceConfig.DEFAULT
```

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/resilience/ResilienceSnippets.kt:defaults"
    ```

=== "Java"

    ```java
    --8<-- "java/website/resilience/ResilienceSnippets.java:defaults"
    ```

## `ResilienceConfig`

`ResilienceConfig` bundles three independent policies, because the three ways a recipe
talks to etcd fail differently and want different answers:

| Field | Governs | Default |
| --- | --- | --- |
| `rpc: RpcResilience` | Blocking calls in the extension layer (`putValue`, `getValue`, `leaseGrant`, …) | `RetryPolicy.bounded(4, 250.milliseconds)`, `operationTimeout = 30s` |
| `watch: WatchResilience` | Re-subscribing a fatally dead watch stream | `RetryPolicy.exponentialBackoff()`, `progressNotify = true` |
| `lease: LeaseResilience` | Re-granting an expired lease and re-establishing its keys | `RetryPolicy.exponentialBackoff()`, `healOperationTimeout = 10s` |

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/resilience/ResilienceSnippets.kt:custom-config"
    ```

=== "Java"

    ```java
    --8<-- "java/website/resilience/ResilienceSnippets.java:custom-config"
    ```

Each of the three has its own `DEFAULT` and `DISABLED` constants, as does
`ResilienceConfig` itself. `ResilienceConfig.DISABLED` is every sub-config disabled at
once — the pre-0.12 behaviour, in which an RPC against an unreachable server parked
forever, a fatally dead watcher was silently dropped, and an expired lease meant the
recipe's keys were gone for good:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/resilience/ResilienceSnippets.kt:disabled"
    ```

=== "Java"

    ```java
    --8<-- "java/website/resilience/ResilienceSnippets.java:disabled"
    ```

!!! warning "`DISABLED` is a compatibility escape hatch, not a tuning knob"

    It exists so a caller who depended on the old semantics can get them back
    deliberately rather than by surprise. If what you actually want is *different*
    pacing, change the `RetryPolicy` — don't turn recovery off.

`ResilienceConfig` also carries the metrics sink shared by all three sub-configs.
`val metrics` reads it; `withMetrics(m)` returns a copy that routes every RPC, watch,
and lease funnel — plus every recipe seam — to `m`. See
[Observability](../observability.md).

## `RetryPolicy`

A `RetryPolicy` answers one question: given that attempt *n* has failed and *elapsed*
time has passed since the first failure, how long should we wait before trying again —
or should we stop?

```kotlin
fun interface RetryPolicy {
  fun nextDelay(attempt: Int, elapsed: Duration): Duration?
}
```

Returning `null` means **give up**. That is not a detail: it is how a recovery loop
terminates, and it is what turns a suspended watcher into a `Failed` one and a
`SUSPENDED` connection into a `LOST` one.

The library ships two factories and two constants:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/resilience/ResilienceSnippets.kt:retry-policy"
    ```

=== "Java"

    ```java
    --8<-- "java/website/resilience/ResilienceSnippets.java:retry-policy"
    ```

`exponentialBackoff` jitters every delay by ±`jitterRatio` on purpose. A fleet of
clients that all lost the same etcd will all start recovering at the same instant;
without jitter they would retry in lockstep and hammer the server just as it comes
back.

Because `RetryPolicy` is a `fun interface`, a lambda is a policy:

```kotlin
--8<-- "kotlin/website/resilience/ResilienceSnippets.kt:custom-retry-policy"
```

!!! note "`nextDelay` is called from background threads"

    The watch dispatcher and the lease healer both call it. Implementations must be
    thread-safe, and must not block — a policy that does I/O to decide stalls the very
    recovery it is pacing.

### Which failures are retried

RPC retries are deliberately narrow. Only three gRPC statuses are retriable:

| Status | Why retrying is safe |
| --- | --- |
| `UNAVAILABLE` | The server was unreachable or shutting down. The call did not run. |
| `INTERNAL` | etcd reported a transport-level fault, not a rejected request. |
| `DEADLINE_EXCEEDED` | Our own attempt deadline fired; a fresh attempt is a fresh chance. |

Everything else — `NOT_FOUND`, `INVALID_ARGUMENT`, `PERMISSION_DENIED`, a compare
failure — propagates unchanged. Those are answers, not accidents: the server
understood the request and said no, and retrying would only produce the same no more
slowly.

!!! danger "Transactions are never retried, whatever the policy says"

    A failed transaction commit is **ambiguous**: it may have been applied before the
    response was lost. Retrying it blindly could apply it twice, which is exactly the
    failure a CAS exists to prevent. `operationTimeout` still bounds the call; the
    retry decision belongs to the recipe's own CAS loop, which re-reads and re-compares
    before trying again.

The retriable-status set lives in `RpcRetry.kt` and is `internal` — it is not part of
the public surface, and there is no hook to extend it. That is intentional: the safe
set is a property of etcd's semantics, not a preference.

### Per-call policies

Every extension in `common/` takes an `RpcResilience` as its last parameter, so a
single latency-sensitive read can be tighter than the recipe holding it:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/resilience/ResilienceSnippets.kt:per-call-rpc"
    ```

=== "Java"

    ```java
    --8<-- "java/website/resilience/ResilienceSnippets.java:per-call-rpc"
    ```

## Watch recovery

jetcd already retries *transient* watch-stream errors itself, transparently and with
revision continuity. What it does **not** recover — and what `WatchResilience` exists
for — are *fatal* deaths: compaction of the watched revision, halt-error statuses, and
`etcdserver: no leader`. Before 0.12 a watcher that died this way simply stopped
delivering events, silently, forever.

```kotlin
--8<-- "kotlin/website/resilience/ResilienceSnippets.kt:watch-recovery"
```

Recovery re-subscribes from the revision just past the last event observed, so nothing
is lost and nothing is duplicated — **except after a compaction**.

### Compaction and `resyncWith`

etcd compacts old revisions away. If your watch was suspended long enough that its
resume revision no longer exists, there is no way to replay the events you missed:
they are gone from the server. The watcher can only re-anchor at a live revision, and
whatever state you derived from the event stream is now wrong.

`resyncWith` is the hook that fixes it. It is invoked only after a compaction, before
re-subscribing: re-read the world, rebuild your derived state from that snapshot, and
return the revision to resume from (typically the GET's `header.revision + 1`).

!!! warning "Without `resyncWith`, a gap is reported but not repaired"

    The watch resumes just past the compacted revision and you get a
    `WatchRecoveryEvent.Resynced` telling you which range was lost. If you keep derived
    state — a cache, a membership set, a counter — that event is a notification that
    your state is now wrong, not a promise that it was fixed. Supply `resyncWith`.

`progressNotify` (on by default) asks etcd for periodic progress notifications so the
resume revision stays fresh even on a quiet key. Without it, a watch on a key nobody
writes keeps an old resume revision, and is much likelier to find it compacted away
after an outage. They arrive at your watch block as `WatchResponse`s with an empty
event list.

### `WatchRecoveryEvent`

```kotlin
--8<-- "kotlin/website/resilience/ResilienceSnippets.kt:recovery-events"
```

| Event | Meaning |
| --- | --- |
| `Suspended(watchedKey, cause)` | The stream errored. Recovery may follow. |
| `Resubscribed(watchedKey, resumeRevision)` | Back, resuming past the last event seen. No gap. |
| `Resynced(watchedKey, compactRevision, anchorRevision)` | Back, but events in `(compactRevision, anchorRevision)` are gone. |
| `Failed(watchedKey, cause)` | The retry policy gave up. This watcher is dead. |

Recipes that own a watch expose these through their own recovery listener (for
example `PathChildrenCache.addRecoveryListener`) and feed them into
`connectionState` — see [Connection state](connection-state.md).

## A gap worth knowing about

Several `withXxx { }` scoped functions **omit** the `resilience` parameter even though
the constructor they delegate to accepts it:

| Scoped function | Takes `resilience`? |
| --- | --- |
| `withNodeCache`, `withLeaderLatch`, `withLeaderObserver` | Yes |
| The typed variants (`withTypedDistributedQueue`, …) | Yes |
| `withPathChildrenCache` | No |
| `withDistributedQueue`, `withDistributedPriorityQueue` | No |
| `withDistributedBarrier`, `withDistributedBarrierWithCount` | No |
| `withLeaderSelector`, `withServiceDiscovery` | No |
| `withDistributedAtomicLong`, `withTransientKeyValue` | No |

This is an inconsistency, not a design statement. Where it bites, construct the recipe
directly and use Kotlin's own `use { }` — you lose nothing but a few characters:

```kotlin
--8<-- "kotlin/website/resilience/ResilienceSnippets.kt:scoped-workaround"
```

!!! note "`DistributedDoubleBarrier` is a special case"

    Neither `withDistributedDoubleBarrier` **nor the constructor** takes a
    `resilience`. It composes two `DistributedBarrierWithCount` instances internally
    with the default config, so there is currently no way to give it a custom one.

## Java callers

The resilience surface is only partly reachable from Java, and it is better to know
that up front than to discover it at the compiler:

| From Java | Status |
| --- | --- |
| `ResilienceConfig(watch, lease, rpc)`, `DEFAULT`, `DISABLED`, `withMetrics` | Works |
| `WatchResilience(retryPolicy, progressNotify, metrics)` | Works — no `Duration` in its signature |
| `RetryPolicy.exponentialBackoff()`, `bounded(int)`, `forever`, `never` | Works |
| `RetryPolicy.exponentialBackoff(initialDelay, …)`, `bounded(int, delay)` | **Unreachable** |
| `RpcResilience(retryPolicy)`, `LeaseResilience(retryPolicy)` | Works |
| Setting `operationTimeout` / `healOperationTimeout` | **Unreachable** |
| Implementing `RetryPolicy` | **Unreachable** |

The cause is the same in every case: `kotlin.time.Duration` is a value class, so any
signature mentioning one is name-mangled on the JVM (`bounded-HG0u8IE`,
`nextDelay-dkRoYf4`) into something Java cannot name or override. A Java caller can
choose among the built-in policies and compose them into a `ResilienceConfig`, but
cannot express a custom duration or a custom policy. See the
[Java guide](../java.md).

## Where to go next

- [Leases and loss](leases.md) — which leases heal, which deliberately do not, and why
  that distinction is the most important one in this library.
- [Connection state](connection-state.md) — the coarse health signal these events feed.
- [Core concepts](../getting-started/concepts.md) — the lifecycle and exception model
  every recipe shares.
- [Locks](../recipes/locks.md) — the recipes where loss is most consequential.
- [Observability](../observability.md) — metering the recovery machinery described here.
