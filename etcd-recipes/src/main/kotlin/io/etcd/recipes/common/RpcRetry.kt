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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.TimeSource

private val RETRIABLE_CODES = setOf(ErrorCode.UNAVAILABLE, ErrorCode.INTERNAL, ErrorCode.DEADLINE_EXCEEDED)

/**
 * Blocks on the future produced by [op], bounding each attempt with
 * [RpcResilience.operationTimeout] and retrying retriable failures (UNAVAILABLE /
 * INTERNAL / DEADLINE_EXCEEDED statuses, or an attempt timeout) under
 * [RpcResilience.retryPolicy]. Non-retriable failures propagate unchanged; policy
 * exhaustion throws [EtcdRecipeRuntimeException] with the attempt count and the
 * last failure as cause. Runs (and sleeps) on the caller's thread — this is the
 * engine behind the blocking extension API.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun <T> retryRpc(
  rpc: RpcResilience,
  opName: String,
  op: () -> CompletableFuture<T>,
): T {
  val start = TimeSource.Monotonic.markNow()
  var attempt = 0
  var lastFailure: Throwable
  while (true) {
    attempt += 1
    try {
      val future = op()
      return if (rpc.operationTimeout.isFinite()) {
        try {
          future.get(rpc.operationTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
          future.cancel(true)
          throw e
        }
      } else {
        future.get()
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw EtcdRecipeRuntimeException("$opName interrupted", e)
    } catch (e: Throwable) {
      if (!e.isRetriableRpcFailure()) throw e
      lastFailure = e
    }
    val delay = rpc.retryPolicy.nextDelay(attempt, start.elapsedNow())
      ?: throw EtcdRecipeRuntimeException("$opName failed after $attempt attempts", lastFailure)
    if (delay > Duration.ZERO) Thread.sleep(delay.inWholeMilliseconds)
  }
}

// internal: the suspend RPC engine in io.etcd.recipes.coroutines shares this predicate
internal fun Throwable.isRetriableRpcFailure(): Boolean =
  generateSequence(this) { it.cause.takeIf { c -> c !== it } }
    .any { t -> t is TimeoutException || (t is EtcdException && t.errorCode in RETRIABLE_CODES) }

/**
 * Blocks on [future] with the [RpcResilience.operationTimeout] applied but NO
 * retry — for transactions, whose failed commits are ambiguous and whose retry
 * decisions belong to the recipes' own CAS loops.
 */
internal fun <T> awaitRpc(
  rpc: RpcResilience,
  opName: String,
  future: CompletableFuture<T>,
): T =
  if (rpc.operationTimeout.isFinite()) {
    try {
      future.get(rpc.operationTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
      future.cancel(true)
      throw EtcdRecipeRuntimeException("$opName timed out after ${rpc.operationTimeout}", e)
    }
  } else {
    future.get()
  }
