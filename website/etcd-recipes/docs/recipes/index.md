# Recipes

Each recipe is a coordination pattern built from etcd's primitives — leases, watches, and
atomic transactions. They share a lifecycle and a failure model, both described in
[Core concepts](../getting-started/concepts.md).

## The catalog

| Package | Recipes |
| --- | --- |
| [`lock`](locks.md) | `DistributedMutex`, `DistributedReadWriteLock`, `DistributedSemaphore` |
| [`election`](election.md) | `LeaderSelector`, `LeaderLatch`, `LeaderObserver`, `Participant` |
| [`barrier`](barriers.md) | `DistributedBarrier`, `DistributedBarrierWithCount`, `DistributedDoubleBarrier` |
| [`queue`](queues.md) | `DistributedQueue`, `DistributedPriorityQueue`, `DistributedWorkQueue`, `TypedDistributedQueue<T>`, `TypedDistributedPriorityQueue<T>` |
| [`cache`](caches.md) | `NodeCache<T>`, `PathChildrenCache`, `TypedPathChildrenCache<T>` |
| [`counter`](counter.md) | `DistributedAtomicLong` |
| [`discovery`](discovery.md) | `ServiceDiscovery`, `ServiceRegistry`, `ServiceCache`, `ServiceProvider`, `ServiceInstance`, `ProviderStrategy` |
| [`keyvalue`](keyvalue.md) | `TransientKeyValue`, `TypedTransientKeyValue<T>` |
| [`common`](../basics/index.md) | Kotlin extensions over jetcd; `EtcdCodec<T>` |
| [`coroutines`](../coroutines/index.md) | Suspending twins, `Flow` event surfaces |

## Choosing one

**I need one process to do this at a time.**
A [`DistributedMutex`](locks.md) if it is a short critical section around a specific
resource. A [`LeaderLatch`](election.md) if it is an ongoing *role* — a scheduler, a
compactor — that one instance should hold for as long as it stays healthy. The difference
is duration and intent: a lock guards a section, a leader owns a job.

**I need at most N at a time.**
[`DistributedSemaphore`](locks.md).

**Many readers, occasional writer.**
[`DistributedReadWriteLock`](locks.md).

**Work must be done once, and survive a worker crash.**
[`DistributedWorkQueue`](queues.md) — at-least-once with visibility timeouts and dead
letters. Not `DistributedQueue`, which drops the item on the floor if a consumer dies
holding it.

**Fan work out, order matters, losses tolerable.**
[`DistributedQueue`](queues.md) or [`DistributedPriorityQueue`](queues.md).

**Everyone waits until everyone is ready.**
[Barriers](barriers.md) — simple for a gate someone opens, counted for an N-party
rendezvous, double for enter-and-leave phase sync.

**Read a value that changes, without hammering etcd.**
[`NodeCache<T>`](caches.md) for one key, [`PathChildrenCache`](caches.md) for a prefix.
Both keep a local view current with a watch.

**Find the instances of a service.**
[Service discovery](discovery.md) — with [`ServiceProvider`](discovery.md) if you want
client-side load balancing and error ejection too.

**Count something across processes.**
[`DistributedAtomicLong`](counter.md).

**Advertise something that disappears if my process dies.**
[`TransientKeyValue`](keyvalue.md).

## What they share

- **Constructors do no I/O.** Work happens in `start()` or on first use.
- **`Closeable`.** `close()` is idempotent; most take `use { }`.
- **`withXxx { }`.** Most have a scoped function that starts and closes for you.
- **Failures surface, they don't throw from worker threads.** `exceptions`, listeners, and
  `connectionState`. See [Core concepts](../getting-started/concepts.md).
- **Holds can be lost.** See [Leases and loss](../resilience/leases.md).
- **Typed variants exist** for queues, caches and key/values via
  [`EtcdCodec<T>`](../typed-values.md).
- **Suspending twins exist** for nearly everything. See [Coroutines](../coroutines/index.md).
- **They are Java-usable.** See the [Java guide](../java.md).

## Composing them

The recipes are built on the same [Kotlin extensions over jetcd](../basics/index.md) that
you have, and they never hide the `Client`. Mixing a recipe with a raw transaction against
your own keys is normal and expected, not a workaround.

The [`EtcdRecipes`](../integrations/index.md) facade is a convenience factory for the
common ones if you would rather not construct each type by hand.
