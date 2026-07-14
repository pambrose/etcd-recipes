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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/**
 * Runs a blocking recipe call on [dispatcher] such that coroutine cancellation
 * interrupts the worker thread, triggering the recipe's existing interrupt-cleanup
 * (lease revoke / queue-entry delete in finally blocks); the cleanup completes before
 * this resumes — `runInterruptible` does not abandon the thread.
 *
 * The blocking RPC engine catches a mid-call `InterruptedException` and rethrows it
 * wrapped in an [EtcdRecipeRuntimeException] (with the interrupt flag re-set), so
 * `runInterruptible` cannot recognize it as cancellation. This unwraps that case:
 * when the surrounding coroutine has been cancelled, a wrapped interrupt is surfaced
 * as the expected `CancellationException` rather than a spurious failure.
 */
internal suspend fun <T> interruptibleOn(
  dispatcher: CoroutineDispatcher,
  block: () -> T,
): T =
  try {
    runInterruptible(dispatcher, block = block)
  } catch (e: EtcdRecipeRuntimeException) {
    if (e.cause is InterruptedException) currentCoroutineContext().ensureActive()
    throw e
  }

/** Runs a blocking recipe call on [Dispatchers.IO]; see [interruptibleOn]. */
internal suspend fun <T> etcdInterruptible(block: () -> T): T = interruptibleOn(Dispatchers.IO, block)
