# Leases and loss

Almost everything a recipe owns in etcd is owned through a **lease**. A barrier key, a
service registration, a lock hold, a leadership claim — each is a key bound to a lease
that the client keeps alive with a stream of renewals. Stop renewing for longer than
the TTL and etcd deletes the key. That is the whole point: it is how a process that
dies stops holding things.

It is also what makes a partition dangerous. Your process is fine. Your network is not.
And etcd, which cannot tell those apart, does exactly what you asked it to do and gives
your key away.

This page is about what the library does in that moment, and — more importantly — about
the one case where the right answer is *nothing at all*.

## The split

Some leases are healed automatically. Others are deliberately, permanently not.

| Lease | Healed? |
| --- | --- |
| `DistributedBarrier`, `DistributedBarrierWithCount` (barrier keys) | Yes |
| `TransientKeyValue` (and `TypedTransientKeyValue`) | Yes |
| `ServiceRegistry` / `ServiceDiscovery` registrations | Yes |
| `DistributedWorkQueue` consumer lease | Yes |
| `LeaderSelector` **participation** key | Yes |
| `DistributedMutex`, `DistributedReadWriteLock` holds | **No** |
| `DistributedSemaphore` permits | **No** |
| `LeaderSelector` / `LeaderLatch` **leadership** lease | **No** |

The line is not arbitrary, and it is not about how hard healing would be. It is about
what the lease *means*.

### Why the first group heals

For a barrier key, a service registration, or a transient key/value, losing the lease
is a **liveness** problem. Nobody else is now doing something incompatible with what you
were doing; the world simply forgot that you exist. A barrier silently unset, a service
that vanished from discovery while its process is perfectly healthy — these are bugs,
and re-establishing the key is both safe and correct.

So the library re-establishes it. `SelfHealingKeepAlive` re-grants a lease, re-runs the
hook that creates the owned keys under the new lease id, and re-registers the keep-alive
— paced by `LeaseResilience.retryPolicy`.

Crucially, re-establishment goes through a **CAS**, not a blind put. If the key
unexpectedly exists — someone else claimed it during your outage — the establish hook
returns `false` and healing is abandoned with a `LeaseEvent.Failed`. Even in the
self-healing group, ownership is never *reclaimed*, only *restored where it is still
free*.

### Why the second group must not heal

For a lock, a semaphore permit, or a leadership claim, lease expiry does not mean the
world forgot you. It means:

!!! danger "etcd has already promoted your successor"

    The moment your lease expired, etcd deleted your lock key. The next waiter's watch
    fired. Another process is — right now, while you are reading this — inside the
    critical section you think you are in.

Healing that lease would re-create your key alongside the new holder's. Two processes
would believe they hold a mutex. The recipe would have converted a detectable failure
into an undetectable one, and the single guarantee it exists to provide — mutual
exclusion — would be gone.

So it doesn't. A lost lock stays lost. This is not the library giving up; it is the
library declining to lie.

!!! note "`DistributedWorkQueue` heals forward, not backward"

    Its consumer lease is re-granted so that *future* claims work, but the claim markers
    made under the dead lease are deliberately gone. That is not a gap in the healing —
    it **is** the visibility contract: a claim whose lease expired means the item is
    reclaimable by someone else, and `WorkItem.ack` returning `false` is how you find
    out. Delivery is at-least-once by design.

!!! note "The asymmetry inside `LeaderSelector`"

    A single `LeaderSelector` holds two leases and treats them differently, which looks
    inconsistent until you apply the rule above. Its **participation** key ("I am a
    candidate") is self-healed: losing it just makes you invisible in
    `getParticipants()`, and re-registering races nobody. Its **leadership** lease is
    not healed: losing it means another candidate may already lead.

## Loss is cooperative

Since the recipe cannot fix a lost lock, it tells you and lets you decide. Three things
happen, and none of them is a thrown exception on a background thread:

- every registered listener fires — `LockLostListener` for locks and permits,
  `LeaseEvent`/step-down for leadership;
- `connectionState` moves to `LOST` (see [Connection state](connection-state.md));
- the matching `unlock()` / `release()` returns `false` instead of throwing.

That last one matters more than it looks. Your `finally { mutex.unlock() }` still runs,
still does the right thing, and quietly reports that there was nothing left to release.
Code written for the happy path does not blow up on the unhappy one — it just tells the
truth if you check. See [Locks](../recipes/locks.md).

### Interruption is opt-in — except where it isn't

| Parameter | Recipe | Default |
| --- | --- | --- |
| `interruptOnLockLoss` | `DistributedMutex`, `DistributedReadWriteLock` | `false` |
| `interruptOnPermitLoss` | `DistributedSemaphore` | `false` |
| `interruptOnLeaseLoss` | `LeaderSelector`, `LeaderLatch` | **`true`** |

The asymmetry is deliberate, and it follows from the shape of the code on each side.

A lock holder is running *your* method body. It has a stack, probably a transaction, and
possibly a half-finished non-idempotent side effect. Interrupting it mid-write is not
automatically safer than letting it finish and fail its own commit — the correct
response depends entirely on what the critical section does, and only you know that. So
the default is to tell you and let the thread run on; opt in with `interruptOnLockLoss`
when you would rather it stop the instant the lock is gone.

A leadership callback is different. `takeLeadership` is *supposed* to run for the whole
term and is usually parked — sleeping, polling, blocked on a queue. If the lease is gone
the term is over, and without an interrupt the callback could sit there indefinitely,
doing leader-work as a non-leader. So the default is to interrupt: it is the only thing
that reliably reaches code parked in `sleep` or IO. Pass `interruptOnLeaseLoss = false`
if your callback checks for step-down itself and would rather not be interrupted.

## `SelfHealingKeepAlive`

The machinery under the self-healing group. Its constructor is `internal`; you reach it
through the `Client.selfHealingKeepAlive` extension:

```kotlin
fun Client.selfHealingKeepAlive(
  ttl: Duration,
  resilience: LeaseResilience = LeaseResilience.DEFAULT,
  leaseListener: LeaseListener? = null,
  establish: (lease: LeaseGrantResponse) -> Boolean,
): SelfHealingKeepAlive
```

`establish` is the contract. It runs synchronously at grant time — a `false` return or a
throw aborts the whole thing — and then again on **every** heal, with the fresh lease. It
must put or CAS the keys it owns under the new lease id and return `true`, or return
`false` to say *ownership is gone and must not be reclaimed*, which emits
`LeaseEvent.Failed` and stops.

The returned object exposes three things: `currentLeaseId` (`-1L` before the first
grant), `isHealthy` (false while a heal is in flight, and after `close()`), and
`close()`, which cancels any pending heal attempt and revokes the current lease so the
owned keys go away promptly rather than lingering until the TTL.

!!! note "What jetcd already handles, and what it doesn't"

    jetcd auto-restarts a keep-alive stream after a *transient* error with the observer
    still registered, so renewal resumes by itself — that surfaces as
    `LeaseEvent.Suspended` and nothing more. What jetcd cannot recover is an *expired*
    lease: renewal stopped past the TTL (`onCompleted`), or etcd reports the lease gone
    (`NOT_FOUND: requested lease not found`). Both mean the keys are deleted, and both
    trigger healing.

### `LeaseEvent`

```kotlin
sealed interface LeaseEvent {
  data class Suspended(val leaseId: Long, val cause: Throwable) : LeaseEvent
  data class Expired(val leaseId: Long, val cause: Throwable?) : LeaseEvent
  data class Restored(val oldLeaseId: Long, val newLeaseId: Long) : LeaseEvent
  data class Failed(val leaseId: Long, val cause: Throwable?) : LeaseEvent
}

fun interface LeaseListener {
  fun onLeaseEvent(event: LeaseEvent)
}
```

| Event | What it tells you |
| --- | --- |
| `Suspended` | The stream errored; jetcd is retrying it. The lease is probably fine. |
| `Expired` | The lease is gone and the keys with it. Healing follows unless the policy forbids it. **Ownership may already be someone else's.** |
| `Restored` | A new lease was granted and the owned keys were re-established under it. Note the id changed. |
| `Failed` | Healing was abandoned — the policy was exhausted, or the establish hook declined. |

Register with `addLeaseListener` on `TransientKeyValue`, `TypedTransientKeyValue`,
`ServiceRegistry`, or `DistributedWorkQueue`. Listeners run on the recipe's own healer
thread, never on jetcd's event loop, so a listener that blocks stalls healing but cannot
deadlock the client. As a `Flow`, see [Flows](../coroutines/flows.md).

!!! warning "`Restored` is not `Nothing happened`"

    Between `Expired` and `Restored` your key genuinely did not exist. Anyone who read
    etcd in that window saw it missing, and anyone waiting for it to disappear got what
    they were waiting for. Healing restores the key; it cannot un-observe the gap.

## The barrier's spurious-lift window

That last warning has a concrete consequence worth spelling out, because
`DistributedBarrier` is where callers meet it first.

A barrier is "set" by the presence of a key. Waiters block until the key is deleted.
If the barrier owner's lease expires during a partition, etcd deletes that key — and
every waiter sees the barrier lift. They proceed. The barrier did its job wrong, and no
amount of healing can take that back: the deletion was real, the watches fired, the
waiters are gone.

What the library does is the best of the available options:

- the healer re-grants and re-arms the barrier via CAS, so *future* waiters block again;
- `LeaseEvent.Expired` is surfaced and recorded on `exceptions`, so the owner knows;
- `connectionState` moves to `LOST`;
- if another client set the barrier meanwhile, the CAS loses and healing is abandoned
  with `Failed` — the barrier stays armed, just not maintained by this instance.

But the window between expiry and re-arm is **unavoidable**, and inside it waiters may
briefly see the barrier lifted. If your barrier guards something that must not start
early, do not rely on the barrier alone: pick a TTL comfortably longer than any
partition you expect to survive, and have the released work re-check a condition that
is authoritative on its own.

## Tuning

`LeaseResilience` has two knobs:

- `retryPolicy` (default `exponentialBackoff()`) — how heal attempts are paced, and
  whether they ever stop. `RetryPolicy.forever` never gives up; `RetryPolicy.never`
  disables healing entirely, restoring the pre-0.12 behaviour where an expired lease
  meant the keys were gone for good.
- `healOperationTimeout` (default `10s`) — the per-RPC deadline on the re-grant, so a
  heal attempt against an unreachable server fails and retries under the policy instead
  of parking forever.

!!! tip "TTL is the real knob"

    Retry pacing decides how fast you come back. The lease TTL decides whether you go
    away at all. The 2-second default (`leaseTtlSecs` on most recipes) is responsive but
    treats a 3-second GC pause as death. If your workload has long pauses or a flaky
    network, raise the TTL before you touch the retry policy.

## Where to go next

- [Locks](../recipes/locks.md) — the recipes on the never-heal side of the split.
- [Connection state](connection-state.md) — how these events become a health signal.
- [Resilience](index.md) — `ResilienceConfig`, `RetryPolicy`, watch recovery.
- [Flows](../coroutines/flows.md) — `LeaseEvent` as a coroutine `Flow`.
- [Core concepts](../getting-started/concepts.md) — leases, TTLs, and the recipe
  lifecycle.
