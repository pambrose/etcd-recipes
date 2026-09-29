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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.common.exception.ErrorCode
import io.etcd.jetcd.common.exception.EtcdException
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.TimeSource

private val RETRIABLE_CODES = setOf(ErrorCode.UNAVAILABLE, ErrorCode.INTERNAL, ErrorCode.DEADLINE_EXCEEDED)

// jetcd's KV, lease, and lock calls fail with raw gRPC statuses (only its older helpers
// convert them to EtcdException), so both forms must count.
private val RETRIABLE_STATUSES = setOf(Status.Code.UNAVAILABLE, Status.Code.INTERNAL, Status.Code.DEADLINE_EXCEEDED)

/**
 * Blocks on the future produced by [op], bounding each attempt with
 * [RpcResilience.operationTimeout] and retrying retriable failures (UNAVAILABLE /
 * INTERNAL / DEADLINE_EXCEEDED statuses, or an attempt timeout) under
 * [RpcResilience.retryPolicy]. Only for calls that are safe to repeat — reads, and
 * calls whose duplicate is harmless (unlock, lease grant); plain writes use [awaitRpc],
 * because a write that failed or timed out may still have been applied. Every failure
 * surfaces as [EtcdRecipeRuntimeException] with the original failure as cause:
 * non-retriable ones at once, retriable ones when the policy is exhausted. An interrupt,
 * during an attempt or the backoff, surfaces the same way with the interrupt flag
 * restored. Runs (and sleeps) on the caller's thread — this is the engine behind the
 * blocking extension API.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount", "SwallowedException") // wrappers dropped, causes kept
internal fun <T> retryRpc(
  rpc: RpcResilience,
  opName: String,
  op: () -> CompletableFuture<T>,
): T {
  val start = TimeSource.Monotonic.markNow()
  var attempt = 0
  var failed = true
  try {
    var lastFailure: Throwable
    while (true) {
      attempt += 1
      try {
        val result = op().awaitBounded(rpc) { throw it } // a timeout is a retriable failure
        failed = false
        return result
      } catch (e: InterruptedException) {
        throw interrupted(opName, e)
      } catch (e: Exception) {
        val cause = e.unwrapped()
        if (!cause.isRetriableRpcFailure()) throw EtcdRecipeRuntimeException("$opName failed: ${cause.message}", cause)
        lastFailure = cause
      }
      val delay = rpc.retryPolicy.nextDelay(attempt, start.elapsedNow())
        ?: throw EtcdRecipeRuntimeException("$opName failed after $attempt attempts", lastFailure)
      if (delay > Duration.ZERO) {
        try {
          Thread.sleep(delay.inWholeMilliseconds)
        } catch (e: InterruptedException) {
          throw interrupted(opName, e)
        }
      }
    }
  } finally {
    rpc.metrics.recordRpc(opName, start.elapsedNow(), attempt, failed)
  }
}

// Blocks on the future with the operation timeout applied; on timeout it cancels the
// future and runs [onTimeout] (which must not return). No retry or metrics — pure await.
private fun <T> CompletableFuture<T>.awaitBounded(
  rpc: RpcResilience,
  onTimeout: (TimeoutException) -> Nothing,
): T =
  if (rpc.operationTimeout.isFinite()) {
    try {
      get(rpc.operationTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
      cancel(true)
      onTimeout(e)
    }
  } else {
    get()
  }

// Restores the interrupt flag that catching InterruptedException cleared, so callers up
// the stack still see it, and reports the interrupt the same way as any other failure.
private fun interrupted(
  opName: String,
  e: InterruptedException,
): EtcdRecipeRuntimeException {
  Thread.currentThread().interrupt()
  return EtcdRecipeRuntimeException("$opName interrupted", e)
}

// future.get wraps the real failure in an ExecutionException (CompletionException from
// join-style paths); report the failure itself.
private fun Throwable.unwrapped(): Throwable =
  if ((this is ExecutionException || this is CompletionException) && cause != null) cause!! else this

// internal: the suspend RPC engine in io.etcd.recipes.coroutines shares this predicate
internal fun Throwable.isRetriableRpcFailure(): Boolean =
  generateSequence(this) { it.cause.takeIf { c -> c !== it } }
    .any { t ->
      t is TimeoutException ||
        (t is EtcdException && t.errorCode in RETRIABLE_CODES) ||
        (t is StatusRuntimeException && t.status.code in RETRIABLE_STATUSES) ||
        (t is StatusException && t.status.code in RETRIABLE_STATUSES)
    }

/**
 * Blocks on [future] with the [RpcResilience.operationTimeout] applied but NO retry —
 * for transactions and plain writes (put, delete, compact), whose failed or timed-out
 * attempts may still have been applied, so a blind retry could re-apply one after a newer
 * write; retry decisions belong to the recipes' own loops. Every failure, including a
 * timeout or an interrupt (with the interrupt flag restored), surfaces as
 * [EtcdRecipeRuntimeException] with the original failure as cause.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount", "SwallowedException") // wrappers dropped, causes kept
internal fun <T> awaitRpc(
  rpc: RpcResilience,
  opName: String,
  future: CompletableFuture<T>,
): T {
  val start = TimeSource.Monotonic.markNow()
  var failed = true
  try {
    val result =
      future.awaitBounded(rpc) { e ->
        throw EtcdRecipeRuntimeException("$opName timed out after ${rpc.operationTimeout}", e)
      }
    failed = false
    return result
  } catch (e: InterruptedException) {
    throw interrupted(opName, e)
  } catch (e: EtcdRecipeRuntimeException) {
    throw e
  } catch (e: Exception) {
    val cause = e.unwrapped()
    throw EtcdRecipeRuntimeException("$opName failed: ${cause.message}", cause)
  } finally {
    rpc.metrics.recordRpc(opName, start.elapsedNow(), attempts = 1, failed = failed)
  }
}
