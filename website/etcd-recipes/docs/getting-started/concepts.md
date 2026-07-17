# Core concepts

Nearly every recipe extends one base class and follows one lifecycle. Learn it once here
and every recipe page gets shorter.

## `EtcdConnector`

`EtcdConnector(client, resilience)` is the shared base for the stateful recipes. It owns
the jetcd `Client` reference, tracks the lifecycle flags, accumulates background-thread
exceptions, and implements `Closeable`. Everything it gives you:

| Member | Purpose |
| --- | --- |
| `exceptions: List<Throwable>` | Failures captured on background threads |
| `hasExceptions: Boolean` | Cheap check — does not allocate the list |
| `clearExceptions()` | Drain after handling |
| `addBackgroundExceptionListener(l)` | Be told instead of polling |
| `connectionState: ConnectionState` | `CONNECTED` / `SUSPENDED` / `RECONNECTED` / `LOST` |
| `addConnectionStateListener(l)` | React to connectivity changes |
| `isHealthy(): Boolean` | Passive — no round trip |
| `ping(): Boolean` | Active — count-only GET |
| `close()` | Idempotent |

The typed decorators (`TypedDistributedQueue<T>`, `TypedPathChildrenCache<T>`,
`TypedTransientKeyValue<T>`) are the exception: they are `Closeable` but **not**
`EtcdConnector`. They expose `val untyped` to reach the table above. So is
`DistributedDoubleBarrier`. See [Typed values](../typed-values.md).

## The lifecycle

### Constructors do no I/O

Constructing a recipe never makes an RPC. Nothing touches the network until you call
`start()` or use the recipe. This means a constructor cannot fail because etcd is down,
which in turn means you can build recipes during application wiring — in a Spring
`@Configuration`, say — without ordering your startup around etcd availability.

!!! warning "One deliberate exception: `TransientKeyValue`"

    `TransientKeyValue` defaults to `autoStart = true`, so constructing one *does* start
    its keep-alive. Pass `autoStart = false` if you want the usual deferred behaviour.
    See [Transient key/values](../recipes/keyvalue.md).

### `start()`, then `close()`

```kotlin
val cache = PathChildrenCache(client, "/services").start()
try {
  // ...
} finally {
  cache.close()
}
```

- Most `start()` methods return `this`, so they chain.
- `close()` is idempotent and safe to call twice.
- Several recipes are **one-shot**: `NodeCache.start()`, `ServiceCache.start()`,
  `ServiceProvider.start()`, `LeaderObserver.start()`, and both `start()` and `close()`
  on `LeaderLatch`. `LeaderSelector`, by contrast, is reusable across terms. Each recipe
  page says which it is.
- `DistributedAtomicLong.start()` is optional — it is invoked on first use.

### `withXxx { }`

Most recipes have a scoped top-level function that constructs, starts, runs your block,
and closes — the shape `use` would give you, minus the ceremony:

```kotlin
withDistributedBarrier(client, "/barriers/ready") {
  waitOnBarrier()
}
```

There are 18 of these, plus `withLock`/`withPermit` on the lock types and
`withServiceCache`/`withServiceProvider` on `ServiceDiscovery`.

!!! note "Two gaps worth knowing"

    `DistributedMutex`, `DistributedReadWriteLock`, `DistributedSemaphore`, `ServiceCache`,
    `ServiceRegistry`, `ServiceProvider` and `DistributedWorkQueue` have **no** top-level
    `withXxx` — construct them and use `use { }`.

    And several `withXxx` overloads omit the `resilience` parameter their constructor
    accepts (`withPathChildrenCache`, `withDistributedQueue`, `withDistributedBarrier*`,
    `withLeaderSelector`, `withServiceDiscovery`, `withDistributedAtomicLong`,
    `withTransientKeyValue`). Construct the recipe directly if you need custom
    [resilience](../resilience/index.md) with one of those.

## How failures reach you

This is the part that differs most from a single-JVM library, and it is worth
understanding before you debug something at 3am.

### Background threads don't throw at you

A recipe's watches and keep-alives run on background threads. There is no call stack of
yours for them to throw into. So instead of losing the exception — or killing a thread
you cannot see — the recipe **records** it:

```kotlin
if (cache.hasExceptions) {
  cache.exceptions.forEach { logger.error(it) { "background failure" } }
  cache.clearExceptions()
}
```

Polling is fine for a batch job. For a service, register a listener:

```kotlin
cache.addBackgroundExceptionListener { context, throwable ->
  logger.error(throwable) { "etcd-recipes failure in $context" }
}
```

`context` identifies the recipe instance — `"DistributedMutex[/locks/orders]"` — so one
listener can serve every recipe in the process. Recipes also push that identity into the
SLF4J **MDC** on their background threads, so it can go into your log pattern
automatically. See [Observability](../observability.md).

### Exception types

The library raises its own types rather than leaking jetcd's:

| Type | Kind | Meaning |
| --- | --- | --- |
| `EtcdRecipeException` | checked | An operation legitimately failed — e.g. no service instance is available |
| `EtcdRecipeRuntimeException` | unchecked | Misuse or an unrecoverable state — e.g. a read→write lock upgrade |

### Losing what you hold

The concept with no single-JVM analogue. A lock, a permit or a leadership term is backed
by a **lease**. If that lease expires — a long GC pause, a network partition — etcd
concludes you are gone and hands your lock to the next waiter. You may still be running,
believing you hold it.

The library's position is that this must be visible, never papered over. The rule is
whether re-establishing the lease could race a successor:

- **A lease that confers exclusivity is never healed** — a mutex or read/write lock hold,
  a semaphore permit, and the *leadership* key of an election. If it expired, etcd already
  promoted someone else; re-granting it would mean two holders.
- **A lease that only confers presence is healed** — barrier keys, `TransientKeyValue`,
  work-queue claims, service registrations, and an election's *participation* entry.
  Losing those is a liveness problem, and re-establishing is safe.

A single `LeaderSelector` holds one of each, which is why it appears on both lists: lose
the leadership lease and your term ends, but your candidacy is restored so you stay in the
running for the next one.

When you lose a hold: the listener fires, `connectionState` becomes `LOST`, and the
matching `unlock()`/`release()` returns `false` rather than throwing. Interrupting the
holding thread is opt-in (`interruptOnLockLoss`, `interruptOnPermitLoss` — both default
`false`; election's `interruptOnLeaseLoss` defaults `true`).

!!! danger "Read the leases page"

    [Leases and loss](../resilience/leases.md) explains the full split and why it is drawn
    where it is. It is the most important page on this site. If you take a distributed lock
    and do not have a plan for losing it, you do not have a lock — you have a hint.

## Watcher callbacks run on an event loop

Watch callbacks originate on jetcd's Vert.x event loop. Blocking that loop — or calling
something that needs another gRPC response before it can return — deadlocks it.

The `Client.watcher` extension hops callbacks onto a dedicated single-thread executor,
which removes the sharpest edge. But a callback must still never block on a lock that
some other thread holds while *it* waits on gRPC. Keep listener bodies short: copy the
event somewhere and get out.

!!! tip

    Many recipes accept a `userExecutor`, letting you run listener callbacks on your own
    pool. That is the clean way to do real work in response to a cache or election event.

## Identity: `clientId`

Recipes that participate in something — locks, elections, barriers, work queues — take a
`clientId` that defaults to a generated value. It is what shows up in
`LeaderSelector.getParticipants()`, in exception contexts, and in logs. Set it to
something you will recognise (a pod name, a hostname) and future-you will be grateful.

## Next

- [Recipes](../recipes/index.md) — the catalog
- [Resilience](../resilience/index.md) — retry policies and reconnection
- [Java guide](../java.md) — facades, overloads, and what is Kotlin-only
