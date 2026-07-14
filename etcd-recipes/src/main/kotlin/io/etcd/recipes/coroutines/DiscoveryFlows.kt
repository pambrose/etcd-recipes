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

@file:Suppress("MatchingDeclarationName")

package io.etcd.recipes.coroutines

import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.discovery.ServiceCacheListener
import io.etcd.recipes.discovery.ServiceInstance
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** A service-cache change, as delivered to [ServiceCacheListener]. */
data class ServiceCacheEvent(
  val eventType: WatchEvent.EventType,
  val isAdd: Boolean,
  val serviceName: String,
  val serviceInstance: ServiceInstance?,
)

/**
 * The service cache's change events as a [Flow]. Collection registers a listener and
 * cancellation removes it; it never starts or closes the cache. The default unlimited
 * [capacity] keeps a slow collector from stalling the cache's watch dispatcher.
 */
fun ServiceCache.eventsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<ServiceCacheEvent> =
  callbackFlow {
    val listener =
      ServiceCacheListener { eventType, isAdd, serviceName, serviceInstance ->
        trySendBlocking(ServiceCacheEvent(eventType, isAdd, serviceName, serviceInstance))
      }
    addListenerForChanges(listener)
    awaitClose { removeListenerForChanges(listener) }
  }.buffer(capacity)

/** The service cache's watch-recovery transitions as a [Flow]; see [eventsAsFlow] for lifecycle. */
fun ServiceCache.recoveryEventsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<WatchRecoveryEvent> =
  callbackFlow {
    val listener = WatchRecoveryListener { event -> trySendBlocking(event) }
    addRecoveryListener(listener)
    awaitClose { removeRecoveryListener(listener) }
  }.buffer(capacity)
