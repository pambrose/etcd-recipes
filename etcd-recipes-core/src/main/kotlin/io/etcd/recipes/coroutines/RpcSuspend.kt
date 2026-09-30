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

import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.isRetriableRpcFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.TimeSource

// Marker box so withTimeoutOrNull's null unambiguously means "timed out" even for
// futures that could complete with null.
private class Boxed<T>(
  val value: T,
)

/**
 * Suspending twin of the blocking retry engine in `common/RpcRetry.kt`: awaits the
 * future produced by [op], bounding each attempt with [RpcResilience.operationTimeout]
 * and retrying retriable failures under [RpcResilience.retryPolicy], backing off with
 * `delay` instead of `Thread.sleep`. As there, only for calls that are safe to repeat;
 * plain writes use [suspendAwaitRpc]. Failures surface as [EtcdRecipeRuntimeException]
 * with the original failure as cause. External cancellation is never retried: it cancels
 * the in-flight future and propagates immediately.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal suspend fun <T> suspendRetryRpc(
  rpc: RpcResilience,
  opName: String,
  op: () -> CompletableFuture<T>,
): T {
  val start = TimeSource.Monotonic.markNow()
  var attempt = 0
  var failed = true // a cancellation counts as a failure, as an interrupt does on the blocking side
  try {
    var lastFailure: Throwable
    while (true) {
      attempt += 1
      val future = op()
      try {
        val boxed =
          if (rpc.operationTimeout.isFinite()) {
            // withTimeoutOrNull instead of withTimeout: an enclosing withTimeout's
            // TimeoutCancellationException must never be mistaken for our attempt
            // timeout — here any CancellationException reaching the catch is external.
            withTimeoutOrNull(rpc.operationTimeout) { Boxed(future.await()) }
          } else {
            Boxed(future.await())
          }
        if (boxed != null) {
          failed = false
          return boxed.value
        }
        future.cancel(true)
        lastFailure = TimeoutException("$opName attempt timed out after ${rpc.operationTimeout}")
      } catch (e: CancellationException) {
        future.cancel(true)
        throw e
      } catch (e: Exception) {
        if (!e.isRetriableRpcFailure()) throw EtcdRecipeRuntimeException("$opName failed: ${e.message}", e)
        lastFailure = e
      }
      val delay = rpc.retryPolicy.nextDelay(attempt, start.elapsedNow())
        ?: throw EtcdRecipeRuntimeException("$opName failed after $attempt attempts", lastFailure)
      if (delay > Duration.ZERO) delay(delay)
    }
  } finally {
    rpc.metrics.recordRpc(opName, start.elapsedNow(), attempt, failed)
  }
}

/**
 * Suspending twin of `awaitRpc`: awaits [future] with the operation timeout applied but
 * NO retry — for transactions and plain writes, whose failed or timed-out attempts may
 * still have been applied; retry decisions belong to the recipes' own loops. Failures
 * surface as [EtcdRecipeRuntimeException] with the original failure as cause.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal suspend fun <T> suspendAwaitRpc(
  rpc: RpcResilience,
  opName: String,
  future: CompletableFuture<T>,
): T {
  val start = TimeSource.Monotonic.markNow()
  var failed = true
  try {
    val result =
      if (rpc.operationTimeout.isFinite()) {
        val boxed = withTimeoutOrNull(rpc.operationTimeout) { Boxed(future.await()) }
        if (boxed == null) {
          future.cancel(true)
          val timeout = TimeoutException("$opName attempt timed out after ${rpc.operationTimeout}")
          throw EtcdRecipeRuntimeException("$opName timed out after ${rpc.operationTimeout}", timeout)
        }
        boxed.value
      } else {
        future.await()
      }
    failed = false
    return result
  } catch (e: CancellationException) {
    future.cancel(true)
    throw e
  } catch (e: EtcdRecipeRuntimeException) {
    throw e
  } catch (e: Exception) {
    throw EtcdRecipeRuntimeException("$opName failed: ${e.message}", e)
  } finally {
    rpc.metrics.recordRpc(opName, start.elapsedNow(), 1, failed)
  }
}
