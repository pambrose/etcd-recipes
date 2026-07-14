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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Runs a blocking recipe call on [Dispatchers.IO]. Coroutine cancellation interrupts
 * the worker thread, which triggers the recipe's existing interrupt-cleanup (lease
 * revoke / queue-entry delete in finally blocks); the cleanup completes before this
 * resumes — `runInterruptible` does not abandon the thread. When cancellation
 * interrupts a thread parked inside the blocking retry engine (which wraps
 * `InterruptedException` in `EtcdRecipeRuntimeException`), the caller still observes
 * `CancellationException`: the cancelled scope wins on resumption.
 */
internal suspend fun <T> etcdInterruptible(block: () -> T): T = runInterruptible(Dispatchers.IO, block = block)
