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

import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.cache.PathChildrenCacheEvent
import io.etcd.recipes.cache.PathChildrenCacheListener
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * The cache's child events (CHILD_ADDED / CHILD_UPDATED / CHILD_REMOVED, plus
 * INITIALIZED when started with [PathChildrenCache.StartMode.POST_INITIALIZED_EVENT])
 * as a [Flow]. Collection registers a listener and cancellation removes it; it never
 * starts or closes the cache.
 *
 * Events emitted while nothing collects are not buffered — to observe INITIALIZED,
 * begin collecting (e.g. signal from `onStart`) before calling
 * `cache.start(POST_INITIALIZED_EVENT)`. The default unlimited [capacity] keeps a
 * slow collector from stalling the cache's watch dispatcher.
 */
fun PathChildrenCache.eventsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<PathChildrenCacheEvent> =
  callbackFlow {
    val listener = PathChildrenCacheListener { event -> trySendBlocking(event) }
    addListener(listener)
    awaitClose { removeListener(listener) }
  }.buffer(capacity)

/** The cache's watch-recovery transitions as a [Flow]; see [eventsAsFlow] for lifecycle. */
fun PathChildrenCache.recoveryEventsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<WatchRecoveryEvent> =
  callbackFlow {
    val listener = WatchRecoveryListener { event -> trySendBlocking(event) }
    addRecoveryListener(listener)
    awaitClose { removeRecoveryListener(listener) }
  }.buffer(capacity)
