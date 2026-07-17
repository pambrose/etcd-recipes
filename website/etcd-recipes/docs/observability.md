# Observability

Distributed coordination fails quietly. A watch that stopped delivering, a lease that
healed three times an hour, a lock whose median wait crept from 2 ms to 800 ms — none of
these throw, and all of them matter. This page covers the three surfaces the library
gives you for seeing them: a metrics SPI, a background-exception channel, and the
logging context that ties background threads back to the recipe that spawned them.

## The `EtcdMetrics` SPI

`EtcdMetrics` is a dependency-free interface the library calls at its seams. The library
itself ships exactly one implementation — `EtcdMetrics.NoOp` — and no metrics
dependency at all.

Every method has an **empty default body**. That single decision is what makes the SPI
cheap: an implementation overrides only the seams it cares about, adding a method to the
interface never breaks an existing implementation, and a caller who wants no metrics
pays nothing.

| Method | Called when |
| --- | --- |
| `recordRpc(opName, duration, attempts, failed)` | A blocking RPC completes. `attempts > 1` means it was retried. |
| `incrementWatchRecovery(kind, key)` | A watcher transitions: `suspended` / `resubscribed` / `resynced` / `failed`. |
| `incrementKeepAlive(kind, leaseId)` | A lease event: `renewal` / `suspended` / `expired` / `restored` / `failed`. |
| `recordLockWait(path, duration, acquired)` | A lock or permit acquisition finishes, successfully or not. |
| `recordLockHold(path, duration)` | A lock or permit is released, timed from grant. |
| `incrementLeadershipTransition(path, becameLeader)` | Leadership is taken (`true`) or relinquished (`false`). |
| `recordQueue(op, path, duration)` | A queue `enqueue` or `dequeue` completes. |
| `recordCacheSync(path, duration, size)` | A cache snapshot loads, with the resulting entry count. |

```kotlin
--8<-- "kotlin/website/observability/MetricsSnippets.kt:custom-metrics"
```

!!! warning "These run on hot threads"

    Seams are hit from RPC-caller threads, the watch dispatcher, and the lease healer.
    An implementation must be thread-safe and must not block: a metrics sink that does
    IO on `recordRpc` adds its latency to every etcd call, and one that blocks the lease
    healer delays the healing it is trying to measure.

Install a sink with `ResilienceConfig.withMetrics(...)`, which returns a copy routing
all three funnels — RPC, watch, and lease — plus every recipe seam to it:

```kotlin
--8<-- "kotlin/website/observability/MetricsSnippets.kt:install-metrics"
```

Without that call, the sink is `NoOp`:

```kotlin
--8<-- "kotlin/website/observability/MetricsSnippets.kt:no-op-metrics"
```

!!! note "`key`, `path`, and `leaseId` are context, not tags"

    They are passed so a sink *can* use them — to log a specific key, or to route by
    prefix. Turning them into metric tags is almost always a mistake: lock paths and
    lease ids are effectively unbounded, and a time series per lease id will take down
    your metrics backend long before it tells you anything. Prefer the low-cardinality
    `kind` / `op` / outcome dimensions.

## Background exceptions

A recipe cannot throw at you from its own healer thread — there is no stack of yours for
the exception to land on. Throwing there would kill the healer and lose the failure. So
recipes never do it. Instead every background failure — a keep-alive death, an abandoned
watcher, a lost lock, a user callback that threw — goes to one sink, which offers both a
push and a pull interface.

```kotlin
fun interface BackgroundExceptionListener {
  fun onException(context: String, throwable: Throwable)
}

data class BackgroundException(val context: String, val throwable: Throwable)
```

`context` is the recipe's own identity — `"DistributedMutex[/locks/orders]"`,
`"PathChildrenCache[/cache/orders]"`, `"TransientKeyValue[/services/api]"` — so one
handler registered across a dozen recipes can still say which one failed.

```kotlin
--8<-- "kotlin/website/observability/MetricsSnippets.kt:background-exceptions"
```

| Surface | Style |
| --- | --- |
| `addBackgroundExceptionListener` / `removeBackgroundExceptionListener` | Push; fires as it happens |
| `exceptions: List<Throwable>` | Pull; a defensive snapshot, safe to iterate |
| `hasExceptions: Boolean` | Pull; cheap check |
| `clearExceptions()` | Pull; reset after handling |
| `backgroundExceptionsAsFlow(capacity)` | Push, as a coroutine `Flow` |

```kotlin
--8<-- "kotlin/website/observability/MetricsSnippets.kt:exceptions-flow"
```

Listeners run on the reporting recipe's own thread — a healer or dispatcher, never
jetcd's event loop. A listener that throws is logged and dropped, never re-recorded, so
notification cannot recurse. See [Core concepts](getting-started/concepts.md).

!!! tip "An empty `exceptions` list is not the same as healthy"

    It means nothing has failed *since you last cleared it*. Pair it with
    `connectionState` and `isHealthy()`, which report the current situation rather than
    the accumulated history. See [Connection state](resilience/connection-state.md).

## Logging context

Recipes wrap their background-thread work with the recipe's identity in the SLF4J
**MDC**, under the key `EtcdConnector.RECIPE_MDC_KEY` (`"etcd.recipe"`). Any prior value
is restored afterwards, so it composes with an MDC you already use.

The value is the same string as the exception `context` above. Put it in your log
pattern and a stray warning from a healer thread stops being anonymous:

```xml
<pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %X{etcd.recipe} %logger{36} - %msg%n</pattern>
```

```
21:04:12.881 WARN  [etcd-lease-healer] TransientKeyValue[/services/api] i.e.r.c.SelfHealingKeepAlive - Lease 7587883142 expired; attempting to re-grant and re-establish
```

Without it, that line arrives from a thread named `etcd-lease-healer` with no clue which
of your recipes owns it — and the healer threads of every recipe in the process share
that name.

## The Micrometer meter catalog

The [`etcd-recipes-micrometer`](integrations/index.md) module supplies
`MicrometerEtcdMetrics`, an `EtcdMetrics` backed by a `MeterRegistry`. Install it the
same way as any other sink — `ResilienceConfig.withMetrics(MicrometerEtcdMetrics(registry))`
— and you get:

| Meter | Type | Tags |
| --- | --- | --- |
| `etcd.rpc` | Timer | `operation` (op name without its key argument), `outcome` (`success` / `failure`) |
| `etcd.rpc.retries` | Counter | `operation` |
| `etcd.watch.recovery` | Counter | `kind` (`suspended` / `resubscribed` / `resynced` / `failed`) |
| `etcd.keepalive` | Counter | `kind` (`renewal` / `suspended` / `expired` / `restored` / `failed`) |
| `etcd.lock.wait` | Timer | `outcome` (`acquired` / `timeout`) |
| `etcd.lock.hold` | Timer | — |
| `etcd.election.transitions` | Counter | `transition` (`acquired` / `relinquished`) |
| `etcd.queue` | Timer | `op` (`enqueue` / `dequeue`) |
| `etcd.cache.sync` | Timer | — |
| `etcd.cache.size` | DistributionSummary | — |

All of them carry whatever common tags the registry already has.

!!! note "Low cardinality, on purpose"

    Keys, lock paths, and lease ids **never** become tags. They reach the sink, and the
    sink deliberately drops them, keeping only the bounded `operation` / `kind` /
    `outcome` dimensions. If you need per-path breakdowns, put the recipes in separate
    registries with distinguishing common tags rather than reintroducing an unbounded
    tag.

### Gauges

The SPI is push-only, which cannot express *current* values. `EtcdGauges` fills the gap
with binders that poll a specific recipe instance on every scrape. Micrometer holds only
a weak reference, so a bound gauge does not keep the recipe alive.

| Binder | Meter | Cost per scrape |
| --- | --- | --- |
| `bindQueueDepth(queue)` | `etcd.queue.depth` | **An RPC** — `size` issues a range count |
| `bindCacheSize(cache)` | `etcd.cache.entries` | In-memory read |
| `bindServiceCacheSize(cache)` | `etcd.cache.entries` | In-memory read |
| `bindAvailablePermits(semaphore)` | `etcd.semaphore.available` | **An RPC** — a range count |
| `bindLeadership(latch)` | `etcd.election.leader` | In-memory read (1.0 while leader) |

!!! warning "Two of those hit etcd on every scrape"

    `bindQueueDepth` and `bindAvailablePermits` poll the server. A 10-second scrape
    interval across 200 instances is 20 range-counts per second against etcd purely for
    dashboards. Bind them where the number is worth the load, and mind the interval.

Binding several instances of the same gauge to one registry needs distinguishing `tags`
— every binder takes them. See [Integrations](integrations/index.md) for setup,
including the Spring Boot starter's auto-configuration.

## What to watch

If you take four things from this page onto a dashboard, take these:

- **`etcd.keepalive{kind="expired"}`** — a lease expired, which for a lock or a
  leadership claim means ownership changed hands unexpectedly. Should be flat at zero.
- **`etcd.watch.recovery{kind="resynced"}`** — a compaction ate events. Anything with
  derived state may now be wrong. See [Resilience](resilience/index.md).
- **`etcd.watch.recovery{kind="failed"}`** — a watcher was abandoned. It is dead and
  will not come back on its own.
- **`etcd.lock.wait{outcome="timeout"}`** relative to `acquired` — contention rising
  before it becomes an outage. See [Locks](recipes/locks.md).
