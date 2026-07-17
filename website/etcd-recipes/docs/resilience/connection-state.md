# Connection state

Every recipe extends `EtcdConnector`, and every `EtcdConnector` maintains one coarse
health signal: `connectionState`.

```kotlin
enum class ConnectionState {
  CONNECTED,
  SUSPENDED,
  RECONNECTED,
  LOST,
}
```

It is **passive**. Nothing polls, nothing pings, nothing runs on a timer. The state is
derived entirely from events the recipe's own watch and lease streams already reported
on their way past — which is why a connector with no active watch and no active lease
reports nothing at all, and sits at `CONNECTED` forever.

| State | Meaning |
| --- | --- |
| `CONNECTED` | The initial state. No failure has been observed yet. |
| `SUSPENDED` | A stream reported an error; recovery — jetcd's or the recipe layer's — is in progress. |
| `RECONNECTED` | A stream was re-established. For a lease, ownership was re-established too. |
| `LOST` | A lease expired, or recovery was abandoned. Ownership may be gone. |

!!! warning "`CONNECTED` is not a health check"

    It is the state a recipe starts in, not one it is promoted back to. A recipe that
    has never talked to etcd and a recipe with a perfectly healthy watch look
    identical from here. To ask whether etcd is actually reachable, use
    [`ping()`](#ishealthy-vs-ping).

## What drives the transitions

The mapping is small enough to memorise, and worth memorising, because it is the whole
reason the states mean what they mean:

| Event | New state |
| --- | --- |
| `WatchRecoveryEvent.Suspended` | `SUSPENDED` |
| `WatchRecoveryEvent.Resubscribed` / `Resynced` | `RECONNECTED` |
| `WatchRecoveryEvent.Failed` | `LOST` |
| `LeaseEvent.Suspended` | `SUSPENDED` |
| `LeaseEvent.Expired` | `LOST` |
| `LeaseEvent.Restored` | `RECONNECTED` |
| `LeaseEvent.Failed` | `LOST` |

Note the sharpest line in that table: **`LeaseEvent.Expired` → `LOST`**, immediately,
before any healing is attempted. That is not pessimism. An expired lease means etcd
already deleted your keys, and for a lock or a leadership claim it means a successor
may already be running. `LOST` says *ownership may not be yours any more* — and it says
it at the only moment when saying it is useful. If healing succeeds, `Restored` moves
you to `RECONNECTED` afterwards. See [Leases and loss](leases.md).

## Listening

```kotlin
fun interface ConnectionStateListener {
  fun stateChanged(newState: ConnectionState, previous: ConnectionState)
}
```

Register with `addConnectionStateListener` and drop with `removeConnectionStateListener`:

```kotlin
--8<-- "kotlin/website/resilience/ConnectionStateSnippets.kt:state-listener"
```

Both the new and the previous state are handed to you, because the transition is often
more informative than the destination — `SUSPENDED → RECONNECTED` is a blip that healed,
while `LOST → RECONNECTED` means you got your key back but somebody may have seen it
missing in between.

!!! note "Transitions are deduplicated, and listeners run on the reporting thread"

    Repeated `Suspended` reports during one outage notify **once**: an equal state is
    dropped, atomically. Listeners run on whichever recipe thread reported the event —
    a healer or a watch dispatcher, never jetcd's event loop — so a listener that
    blocks stalls that recipe's recovery, and a listener that throws is recorded on
    `exceptions` under the context `connection-state-listener` rather than escaping.

## `isHealthy()` vs `ping()`

Two questions that look the same and are not:

| | `isHealthy()` | `ping()` |
| --- | --- | --- |
| Cost | Free — no RPC | One round trip |
| Answers | "Has anything gone irrecoverably wrong that I already know about?" | "Can I reach etcd right now?" |
| Implementation | `connectionState != LOST && !closed` | Count-only GET through the RPC retry/timeout funnel |
| Fails how | Cannot fail | Returns `false` rather than throwing |

```kotlin
--8<-- "kotlin/website/resilience/ConnectionStateSnippets.kt:health-check"
```

The distinction maps neatly onto Kubernetes-style probes:

- **Liveness** wants `isHealthy()`. It is called often, must not add load, and is asking
  whether *this process* is still in a workable state. A liveness probe that issues an
  RPC turns an etcd hiccup into a fleet-wide restart storm — precisely the wrong
  response to a shared dependency being briefly slow.
- **Readiness** wants `ping()`. It is asking whether this instance can serve traffic
  that will need etcd, and that genuinely requires asking etcd. Being told "no" here
  removes one pod from a load balancer; it does not kill anything.

`ping()` writes nothing: it is a count-only GET against a fixed probe key that need not
exist. It routes through the same retry and timeout funnel as every other RPC, so the
recipe's `RpcResilience` decides how long it may take before returning `false`.

There is also a `Client`-level counterpart for code that holds a client but no recipe —
a readiness endpoint wired up before any recipe is constructed, say:

```kotlin
--8<-- "kotlin/website/resilience/ConnectionStateSnippets.kt:client-ping"
```

!!! tip "Give the probe its own `RpcResilience`"

    The 30-second default `operationTimeout` is right for a lock acquisition and wrong
    for a health endpoint. A probe that retries for half a minute is not reporting
    health, it is hiding it. `RetryPolicy.never` with a 2-second deadline gives a fast,
    honest answer.

## As a `Flow`

`connectionStateAsFlow()` emits the current state on collection and then every
transition. It is conflated, so a slow collector sees the latest state rather than every
intermediate one, and it is cold — collection registers the listener, cancellation
removes it, and neither starts nor closes the recipe.

```kotlin
--8<-- "kotlin/website/resilience/ConnectionStateSnippets.kt:state-flow"
```

Use `.stateIn(scope)` for a hot `StateFlow` shared across collectors. See
[Flows](../coroutines/flows.md).

## Where to go next

- [Leases and loss](leases.md) — what `LOST` actually costs you, per recipe.
- [Resilience](index.md) — the retry policies that decide how long `SUSPENDED` lasts.
- [Observability](../observability.md) — exporting this alongside the recovery counters.
- [Core concepts](../getting-started/concepts.md) — the `EtcdConnector` lifecycle.
