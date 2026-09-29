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

/**
 * Thrown by the suspend `withLock` / `withPermit` when the lock or permit they hold is
 * lost (its lease expired) while their action runs, on a recipe built with
 * `interruptOnLockLoss` / `interruptOnPermitLoss`. The action is cancelled first. Unlike a
 * bare `CancellationException`, this fails the caller rather than quietly ending it.
 */
class HoldLostException(
  message: String,
  cause: Throwable? = null,
) : EtcdRecipeRuntimeException(message, cause)
