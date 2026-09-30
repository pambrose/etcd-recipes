/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.etcd.recipes.lock

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * A distributed, reentrant, per-thread-owned lock.
 *
 * Deliberately NOT [java.util.concurrent.locks.Lock]: `newCondition()` is not
 * implementable on etcd, a no-argument `tryLock()` invites non-distributed
 * assumptions, and lock ownership here can be *lost* (lease expiry during a
 * partition) — see [addLockLostListener]. Ownership is cooperative after a loss:
 * the dispossessed thread keeps running until it observes [isHeldByCurrentThread]
 * turn false, its listener firing, or (opt-in) an interrupt.
 */
interface EtcdLock {
  /** Acquires the lock, waiting as long as it takes. Interruptible. */
  @Throws(InterruptedException::class)
  fun lock()

  /** Acquires within [timeout]: true when acquired, false when time ran out. */
  @Throws(InterruptedException::class)
  fun tryLock(timeout: Duration): Boolean

  @Throws(InterruptedException::class)
  fun tryLock(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean

  /**
   * Releases one hold. Returns true when a hold was released or decremented;
   * false when this thread's hold had already been lost (lease expiry) or was
   * released by `close()`. Throws [IllegalMonitorStateException] when the calling
   * thread never held the lock.
   */
  fun unlock(): Boolean

  val isHeldByCurrentThread: Boolean

  /** Advisory: whether any holder (any thread, any process) currently holds it. */
  val isLocked: Boolean

  /** The calling thread's reentrant hold count (0 when it is not the owner). */
  val holdCount: Int

  /**
   * The calling thread's fencing token: a number etcd assigned to its hold, larger than any
   * earlier conflicting holder's; -1 when it holds nothing. Hand it to a downstream resource
   * that keeps the largest token it has seen and rejects smaller ones: a holder that lost the
   * lock (a pause past its lease) but hasn't noticed yet can then no longer act on it.
   */
  val fencingToken: Long

  fun addLockLostListener(listener: LockLostListener)

  fun removeLockLostListener(listener: LockLostListener)
}

/**
 * The lock recipes' default lease TTL. Longer than other recipes' 2 seconds: a holder whose
 * lease lapses loses the lock, so a GC pause or network blip of a couple of seconds must not
 * cost it. The trade-off is that a crashed holder's lock takes up to this long to free.
 */
internal const val DEFAULT_LOCK_TTL_SECS = 10L

/**
 * Whether this lock interrupts its holder when a hold is lost (`interruptOnLockLoss`).
 * The suspend `withLock` cancels its action instead, since its holder is a coroutine.
 */
internal val EtcdLock.interruptsHolderOnLoss: Boolean
  get() =
    when (this) {
      is DistributedMutex -> interruptOnLockLoss
      is DistributedReadWriteLock.LockView -> interruptsOnLoss
      else -> false
    }

/**
 * Notified when a held lock is lost (its lease expired). Runs on the lock's notifier thread,
 * never on jetcd's lease callback thread, so it may block or make RPCs; it delays only the
 * lock's later notifications.
 */
fun interface LockLostListener {
  fun onLockLost(cause: Throwable?)
}

/** Runs [block] under the lock, releasing on every exit path. */
inline fun <T> EtcdLock.withLock(block: () -> T): T {
  lock()
  try {
    return block()
  } finally {
    unlock()
  }
}
