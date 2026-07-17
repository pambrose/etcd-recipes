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

  fun addLockLostListener(listener: LockLostListener)

  fun removeLockLostListener(listener: LockLostListener)
}

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
