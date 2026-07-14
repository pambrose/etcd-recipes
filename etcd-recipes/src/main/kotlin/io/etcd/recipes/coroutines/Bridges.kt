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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Runs a blocking recipe call on [dispatcher] such that coroutine cancellation
 * interrupts the worker thread, triggering the recipe's existing interrupt-cleanup
 * (lease revoke / queue-entry delete in finally blocks); the cleanup completes before
 * this resumes — `runInterruptible` does not abandon the thread.
 *
 * The blocking RPC engine catches a mid-call `InterruptedException` and rethrows it
 * wrapped in an [EtcdRecipeRuntimeException] (re-setting the interrupt flag first, so a
 * subsequent RPC on the same thread — e.g. a paginated re-fetch — can wrap a second
 * time). `runInterruptible` cannot recognize either as cancellation. Inside
 * `runInterruptible` the worker thread is interrupted ONLY by coroutine cancellation,
 * so an `InterruptedException` anywhere in the cause chain reaching here unambiguously
 * means the coroutine was cancelled — surface it as `CancellationException`. (Walking
 * the whole chain matters: the interrupt can be wrapped more than once. And an
 * `ensureActive()`/`isActive` check is racy — the interrupt can be delivered a hair
 * before the `Job`'s state flips to cancelled, which let the failure escape.)
 */
internal suspend fun <T> interruptibleOn(
  dispatcher: CoroutineDispatcher,
  block: () -> T,
): T =
  try {
    runInterruptible(dispatcher, block = block)
  } catch (e: EtcdRecipeRuntimeException) {
    val interrupted =
      generateSequence<Throwable>(e) { it.cause.takeIf { cause -> cause !== it } }
        .any { it is InterruptedException }
    if (interrupted) throw CancellationException("Cancelled during a blocking etcd call")
    throw e
  }

/** Runs a blocking recipe call on [Dispatchers.IO]; see [interruptibleOn]. */
internal suspend fun <T> etcdInterruptible(block: () -> T): T = interruptibleOn(Dispatchers.IO, block)
