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

import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.LeaseListener
import io.etcd.recipes.discovery.ServiceRegistry
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.queue.DistributedWorkQueue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * This key/value's self-healing lease events (Suspended / Expired / Restored /
 * Failed) as a [Flow]. Collection registers a listener and cancellation removes it;
 * it never starts or closes the recipe. Lease events fire from the recipe's healer
 * thread into the default unlimited buffer, so a slow collector never stalls it.
 */
fun TransientKeyValue.leaseEventsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<LeaseEvent> =
  callbackFlow {
    val listener = LeaseListener { event -> trySendBlocking(event) }
    addLeaseListener(listener)
    awaitClose { removeLeaseListener(listener) }
  }.buffer(capacity)

/** The work queue's consumer-lease events as a [Flow]; see [TransientKeyValue.leaseEventsAsFlow]. */
fun DistributedWorkQueue.leaseEventsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<LeaseEvent> =
  callbackFlow {
    val listener = LeaseListener { event -> trySendBlocking(event) }
    addLeaseListener(listener)
    awaitClose { removeLeaseListener(listener) }
  }.buffer(capacity)

/** The registry's registration-lease events as a [Flow]; see [TransientKeyValue.leaseEventsAsFlow]. */
fun ServiceRegistry.leaseEventsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<LeaseEvent> =
  callbackFlow {
    val listener = LeaseListener { event -> trySendBlocking(event) }
    addLeaseListener(listener)
    awaitClose { removeLeaseListener(listener) }
  }.buffer(capacity)
