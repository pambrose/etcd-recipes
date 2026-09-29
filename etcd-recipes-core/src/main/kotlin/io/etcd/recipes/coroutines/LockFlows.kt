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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** A lost-lock notification carrying the (optional) cause. */
data class LockLostEvent(
  val cause: Throwable?,
)

/** A lost-permit notification carrying the (optional) cause. */
data class PermitLostEvent(
  val cause: Throwable?,
)

/**
 * Emits a [LockLostEvent] whenever a held lock is lost (its lease expired). Collection
 * registers a listener and cancellation removes it; it never closes the lock.
 *
 * The listener runs on the lock's notifier thread, which must never block, so the channel
 * is unconditionally unlimited (no backpressure option) and sends never wait.
 */
fun EtcdLock.lockLostAsFlow(): Flow<LockLostEvent> =
  callbackFlow {
    val listener = LockLostListener { cause -> trySend(LockLostEvent(cause)) }
    addLockLostListener(listener)
    awaitClose { removeLockLostListener(listener) }
  }.buffer(Channel.UNLIMITED)

/**
 * Emits a [PermitLostEvent] whenever a held permit is lost. Same lifecycle and
 * notifier-thread constraint as [lockLostAsFlow].
 */
fun DistributedSemaphore.permitLostAsFlow(): Flow<PermitLostEvent> =
  callbackFlow {
    val listener = PermitLostListener { cause -> trySend(PermitLostEvent(cause)) }
    addPermitLostListener(listener)
    awaitClose { removePermitLostListener(listener) }
  }.buffer(Channel.UNLIMITED)
