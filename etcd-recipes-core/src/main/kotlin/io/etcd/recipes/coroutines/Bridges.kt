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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.concurrent.atomics.AtomicReference

/**
 * Runs a blocking recipe call on [dispatcher] such that coroutine cancellation
 * interrupts the worker thread, triggering the recipe's existing interrupt-cleanup
 * (lease revoke / queue-entry delete in finally blocks); the cleanup completes before
 * this resumes — `runInterruptible` does not abandon the thread.
 *
 * A recipe reports that interrupt however its call path does: the blocking RPC engine
 * wraps it (possibly more than once), registration re-wraps it in a checked
 * `EtcdRecipeException`, and some paths replace it with a new exception that has no cause.
 * So a failure is classified by the caller's job instead: `JobSupport` flips the job to
 * cancelling before it interrupts the worker, so a failure from a no-longer-active caller
 * is its cancellation. An `InterruptedException` anywhere in the cause chain is a second
 * signal. Either way the original failure is attached as the cancellation's cause. (So
 * it catches every `Exception` on purpose: a cancelled call can surface as any of them.)
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> interruptibleOn(
  dispatcher: CoroutineDispatcher,
  block: () -> T,
): T =
  try {
    runInterruptible(dispatcher, block = block)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    val interrupted =
      generateSequence<Throwable>(e) { it.cause.takeIf { cause -> cause !== it } }
        .any { it is InterruptedException }
    if (interrupted || !currentCoroutineContext().isActive)
      throw CancellationException("Cancelled during a blocking etcd call", e)
    throw e
  }

/** Runs a blocking recipe call on [Dispatchers.IO]; see [interruptibleOn]. */
internal suspend fun <T> etcdInterruptible(block: () -> T): T = interruptibleOn(Dispatchers.IO, block)

private class Acquired<T>(
  val value: T,
)

/**
 * [interruptibleOn] for a call that acquires something (a lock, a permit, a queue item).
 * `withContext` discards the result of a block that completed after its caller was
 * cancelled, which would leak what [acquire] got. So [undo] gives it back first,
 * non-cancellably and on the same [dispatcher] (a lock's release must run on the thread
 * that acquired it), and then the cancellation propagates. A failing [undo] is attached
 * to it as suppressed rather than replacing it.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> interruptibleAcquire(
  dispatcher: CoroutineDispatcher,
  acquire: () -> T,
  undo: (T) -> Unit,
): T {
  val acquired = AtomicReference<Acquired<T>?>(null)
  try {
    return interruptibleOn(dispatcher) { acquire().also { acquired.store(Acquired(it)) } }
  } catch (e: CancellationException) {
    acquired.load()?.let { held ->
      try {
        withContext(NonCancellable) { runInterruptible(dispatcher) { undo(held.value) } }
      } catch (undoFailure: Exception) {
        e.addSuppressed(undoFailure)
      }
    }
    throw e
  }
}

/**
 * A single-thread dispatcher for one call. Lock ownership is thread-pinned, so a lock's
 * acquire and release must run on the same thread. And a permit or lock records its
 * acquiring thread as the one to interrupt on loss, so that thread must not be a shared
 * worker that goes on to run other coroutines. Close it when the call is done.
 */
internal fun confinedDispatcher(name: String): ExecutorCoroutineDispatcher =
  Executors
    .newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    .asCoroutineDispatcher()
