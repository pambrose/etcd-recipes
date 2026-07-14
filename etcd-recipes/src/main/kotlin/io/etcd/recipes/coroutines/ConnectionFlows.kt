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

import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.ConnectionStateListener
import io.etcd.recipes.common.EtcdConnector
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The recipe's [ConnectionState] as a [Flow]: emits the current state on collection,
 * then every transition (CONNECTED / SUSPENDED / RECONNECTED / LOST). Conflated —
 * a slow collector sees the latest state, not every intermediate one.
 *
 * Collection registers a listener and cancellation removes it; it never starts or
 * closes the recipe. The returned flow is cold — use `.stateIn(scope)` for a hot
 * [kotlinx.coroutines.flow.StateFlow] shared across collectors.
 */
fun EtcdConnector.connectionStateAsFlow(): Flow<ConnectionState> =
  callbackFlow {
    val listener = ConnectionStateListener { newState, _ -> trySendBlocking(newState) }
    // Emit the current state first so a late collector is not left blind until the
    // next transition.
    trySendBlocking(connectionState)
    addConnectionStateListener(listener)
    awaitClose { removeConnectionStateListener(listener) }
  }.conflate()
