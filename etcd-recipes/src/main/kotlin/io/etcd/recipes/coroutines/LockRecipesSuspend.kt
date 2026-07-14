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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.Duration

// One dedicated thread per withLock call: EtcdLock ownership is thread-pinned, so
// acquire and release MUST run on the same thread. Lock calls are rare enough that
// a short-lived executor per call is cheap.
private fun confinedLockDispatcher(): ExecutorCoroutineDispatcher =
  Executors
    .newSingleThreadExecutor { r -> Thread(r, "etcd-suspend-lock").apply { isDaemon = true } }
    .asCoroutineDispatcher()

/**
 * Runs [action] while holding this lock, releasing it on every exit path —
 * including cancellation of [action] (the release leg runs non-cancellably).
 *
 * [EtcdLock] holds are **thread-owned** (mutex and read-write lock pin ownership to
 * the acquiring thread), so this scoped form is the ONLY suspend surface for them:
 * acquisition and release are confined to one dedicated thread per call, while
 * [action] runs in the caller's coroutine. Raw suspend `lock()`/`unlock()` pairs
 * would land on different dispatcher threads and throw
 * [IllegalMonitorStateException].
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
  val confined = confinedLockDispatcher()
  try {
    interruptibleOn(confined) { lock() }
    try {
      return action()
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
  val confined = confinedLockDispatcher()
  try {
    if (!interruptibleOn(confined) { tryLock(timeout) }) return null
    try {
      return action()
    } finally {
      withContext(NonCancellable) { runInterruptible(confined) { unlock() } }
    }
  } finally {
    confined.close()
  }
}

/**
 * Suspending twin of [DistributedSemaphore.acquire]. Semaphore holds are
 * instance-level (any thread may release), so the split acquire/release surface is
 * safe under dispatcher hopping — unlike [EtcdLock], which is scoped-only.
 */
suspend fun DistributedSemaphore.awaitAcquire(): Unit = etcdInterruptible { acquire() }

/** Suspending twin of [DistributedSemaphore.tryAcquire]. */
suspend fun DistributedSemaphore.awaitTryAcquire(timeout: Duration): Boolean = etcdInterruptible { tryAcquire(timeout) }

/** Suspending twin of [DistributedSemaphore.release]. */
suspend fun DistributedSemaphore.awaitRelease(): Boolean = etcdInterruptible { release() }

/** Suspending twin of [DistributedSemaphore.availablePermits]. */
suspend fun DistributedSemaphore.awaitAvailablePermits(): Int = etcdInterruptible { availablePermits() }

/**
 * Runs [action] while holding a permit, releasing it on every exit path —
 * including cancellation of [action] (the release leg runs non-cancellably).
 * Same dual-star-import caveat as [withLock].
 */
suspend fun <T> DistributedSemaphore.withPermit(action: suspend () -> T): T {
  awaitAcquire()
  try {
    return action()
  } finally {
    // Instance-held permits: any thread may release, so a plain IO thread is fine
    withContext(NonCancellable) { runInterruptible(Dispatchers.IO) { release() } }
  }
}
