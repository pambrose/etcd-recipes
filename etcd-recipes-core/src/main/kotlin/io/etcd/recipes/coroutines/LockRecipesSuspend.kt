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

package io.etcd.recipes.coroutines

import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.lock.EtcdLock
import io.etcd.recipes.lock.LockLostListener
import io.etcd.recipes.lock.PermitLostListener
import io.etcd.recipes.lock.interruptsHolderOnLoss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration

/**
 * Runs [action] while holding this lock, releasing it on every exit path —
 * including cancellation of [action] (the release leg runs non-cancellably), and a
 * cancellation that lands just as the lock was acquired (the lock is released before
 * the cancellation propagates).
 *
 * [EtcdLock] holds are **thread-owned** (mutex and read-write lock pin ownership to
 * the acquiring thread), so this scoped form is the ONLY suspend surface for them:
 * acquisition and release are confined to one dedicated thread per call, while
 * [action] runs in the caller's coroutine. Raw suspend `lock()`/`unlock()` pairs
 * would land on different dispatcher threads and throw
 * [IllegalMonitorStateException]. For the same reason the thread-keyed properties
 * (`isHeldByCurrentThread`, `holdCount`) don't describe a suspend holder.
 *
 * On a lock built with `interruptOnLockLoss`, losing this hold (its lease expired)
 * cancels [action], and this throws [HoldLostException]: the suspend counterpart of
 * interrupting the holder thread. Without that option, [action] keeps running, as a
 * blocking holder does.
 *
 * NOT reentrant: each call is an independent acquisition on a fresh thread —
 * nesting `withLock` on the same lock self-deadlocks (like kotlinx `Mutex`), and
 * the blocking API's write→read downgrade does not apply across calls. Cancelling
 * a coroutine parked in acquisition interrupts it, which revokes the acquisition
 * lease and leaves nothing queued.
 *
 * A file star-importing both `io.etcd.recipes.lock.*` and
 * `io.etcd.recipes.coroutines.*` gets a compile-time ambiguity error on
 * `withLock { }` — import one of the two explicitly.
 */
suspend fun <T> EtcdLock.withLock(action: suspend () -> T): T {
  val confined = confinedDispatcher("etcd-suspend-lock")
  try {
    interruptibleAcquire(confined, { lock() }, { unlock() })
    try {
      return holdingLock(confined, action)
    } finally {
      // NonCancellable: the release leg must run even when action() was cancelled
      withContext(NonCancellable) { runInterruptible(confined) { unlock() } }
    }
  } finally {
    confined.close()
  }
}

/**
 * Bounded variant of [withLock]: runs [action] if the lock is acquired within
 * [timeout], else returns null without queuing anything (the waiter's lease is
 * revoked). A null return always means "not acquired".
 */
suspend fun <T> EtcdLock.withLock(
  timeout: Duration,
  action: suspend () -> T,
): T? {
  val confined = confinedDispatcher("etcd-suspend-lock")
  try {
    if (!interruptibleAcquire(confined, { tryLock(timeout) }, { acquired -> if (acquired) unlock() })) return null
    try {
      return holdingLock(confined, action)
    } finally {
      withContext(NonCancellable) { runInterruptible(confined) { unlock() } }
    }
  } finally {
    confined.close()
  }
}

// Runs [action] under a lock held on [confined], cancelling it if the hold is lost and
// the lock cancels its holder on loss. The confined thread owns the hold and sits idle
// while [action] runs, so it answers whether the hold is still there.
private suspend fun <T> EtcdLock.holdingLock(
  confined: kotlinx.coroutines.CoroutineDispatcher,
  action: suspend () -> T,
): T {
  if (!interruptsHolderOnLoss) return action()
  return cancellingOnLoss(
    "The lock",
    register = { onLoss ->
      val listener = LockLostListener { cause -> onLoss(cause) }
      addLockLostListener(listener)
      return@cancellingOnLoss { removeLockLostListener(listener) }
    },
    stillHeld = { withContext(confined) { isHeldByCurrentThread } },
    action = action,
  )
}

/**
 * Suspending twin of [DistributedSemaphore.acquire]. Semaphore holds are
 * instance-level (any thread may release), so the split acquire/release surface is
 * safe under dispatcher hopping — unlike [EtcdLock], which is scoped-only. The acquire
 * runs on its own short-lived thread, which the semaphore records as the permit's
 * holder, and a permit acquired just as the caller is cancelled is released again.
 */
suspend fun DistributedSemaphore.awaitAcquire(): Unit =
  onOwnThread { thread ->
  interruptibleAcquire(thread, { acquire() }, { release() })
}

/** Suspending twin of [DistributedSemaphore.tryAcquire]; see [awaitAcquire]. */
suspend fun DistributedSemaphore.awaitTryAcquire(timeout: Duration): Boolean =
  onOwnThread { thread ->
    interruptibleAcquire(thread, { tryAcquire(timeout) }, { acquired -> if (acquired) release() })
  }

/** Suspending twin of [DistributedSemaphore.release]. */
suspend fun DistributedSemaphore.awaitRelease(): Boolean = etcdInterruptible { release() }

/** Suspending twin of [DistributedSemaphore.availablePermits]. */
suspend fun DistributedSemaphore.awaitAvailablePermits(): Int = etcdInterruptible { availablePermits() }

/**
 * Runs [action] while holding a permit, releasing it on every exit path —
 * including cancellation of [action] (the release leg runs non-cancellably), and a
 * cancellation that lands just as the permit was acquired.
 *
 * On a semaphore built with `interruptOnPermitLoss`, losing this call's permit (its
 * lease expired) cancels [action], and this throws [HoldLostException]. Other holders
 * on the same instance are unaffected. Same dual-star-import caveat as [withLock].
 */
suspend fun <T> DistributedSemaphore.withPermit(action: suspend () -> T): T {
  val holder = onOwnThread { thread ->
    interruptibleAcquire(thread, { acquire().let { Thread.currentThread() } }, { release() })
  }
  try {
    if (!interruptOnPermitLoss) return action()
    return cancellingOnLoss(
      "The permit",
      register = { onLoss ->
        val listener = PermitLostListener { cause -> onLoss(cause) }
        addPermitLostListener(listener)
        return@cancellingOnLoss { removePermitLostListener(listener) }
      },
      stillHeld = { holdsPermitAcquiredOn(holder) },
      action = action,
    )
  } finally {
    // Instance-held permits: any thread may release, so a plain IO thread is fine
    withContext(NonCancellable) { runInterruptible(Dispatchers.IO) { release() } }
  }
}

// Runs [block] with a dedicated thread (see confinedDispatcher), closed afterward.
private suspend fun <T> onOwnThread(block: suspend (kotlinx.coroutines.CoroutineDispatcher) -> T): T {
  val thread = confinedDispatcher("etcd-suspend-semaphore")
  try {
    return block(thread)
  } finally {
    thread.close()
  }
}

// Runs [action], cancelling it when a loss reported through [register] turns out to be
// this call's hold ([stillHeld] false). Then throws HoldLostException rather than the
// action's CancellationException, which a launched caller would treat as a quiet
// cancellation. A loss that happened before the listener was registered is caught by
// checking [stillHeld] once up front.
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> cancellingOnLoss(
  what: String,
  register: (onLoss: (Throwable?) -> Unit) -> () -> Unit,
  stillHeld: suspend () -> Boolean,
  action: suspend () -> T,
): T {
  val losses = Channel<Throwable?>(Channel.UNLIMITED)
  val unregister = register { cause -> losses.trySend(cause) }
  val lost = AtomicReference<HoldLostException?>(null)
  try {
    return coroutineScope {
      val work = async { action() }
      val watcher =
        launch {
          var cause: Throwable? = null
          while (stillHeld()) cause = losses.receive()
          lost.store(HoldLostException("$what was lost while its holder was running", cause))
          work.cancel(CancellationException("$what was lost", cause))
        }
      try {
        work.await()
      } catch (e: CancellationException) {
        lost.load()?.let { throw it }
        throw e
      } finally {
        watcher.cancel()
      }
    }
  } finally {
    unregister()
    losses.close()
  }
}
