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

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchResponse
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.watcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.transform

/**
 * An element of a [watchAsFlow] stream: either a watch response from etcd or a
 * resilience transition of the underlying watcher. Recovery events are surfaced
 * in-band because a resilient watch that silently hides suspension, resubscription,
 * or a compaction resync invites derived-state corruption in the collector.
 */
sealed interface WatchFlowEvent {
  /** A watch response from the stream (events + header). */
  data class Response(
    val response: WatchResponse,
  ) : WatchFlowEvent

  /** A resilience transition: Suspended / Resubscribed / Resynced / Failed. */
  data class Recovery(
    val event: WatchRecoveryEvent,
  ) : WatchFlowEvent
}

/**
 * Watches [keyName] as a [Flow], backed by the same resilient watcher as the blocking
 * API: fatal stream deaths are recovered per [resilience], and after a compaction
 * [resyncWith] is invoked so the caller can re-read its world and return the new
 * anchor revision. Watch responses and recovery transitions arrive in-band as
 * [WatchFlowEvent]s, in order.
 *
 * Cancelling the collector closes the watcher (bounded by its ≤5s close contract).
 * The default [capacity] of [Channel.UNLIMITED] guarantees the watcher's dispatcher
 * thread — which also runs recovery and resync — is never stalled by a slow
 * collector; a bounded capacity opts into backpressure that pauses THIS watch's
 * delivery and recovery while the buffer is full.
 */
fun Client.watchAsFlow(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  resilience: WatchResilience = WatchResilience.DEFAULT,
  resyncWith: (() -> Long)? = null,
  capacity: Int = Channel.UNLIMITED,
): Flow<WatchFlowEvent> =
  callbackFlow {
    // trySendBlocking: never blocks under the UNLIMITED default; on a bounded
    // buffer it parks the watcher's own dispatcher thread (opt-in backpressure);
    // on an already-cancelled collector it reports closed instead of throwing.
    val watcher =
      watcher(
        keyName,
        option,
        resilience,
        recoveryListener = WatchRecoveryListener { event -> trySendBlocking(WatchFlowEvent.Recovery(event)) },
        resyncWith = resyncWith,
      ) { response -> trySendBlocking(WatchFlowEvent.Response(response)) }
    awaitClose { watcher.close() }
  }.buffer(capacity)

/**
 * Convenience over [watchAsFlow]: flattens responses into their [WatchEvent]s and
 * drops recovery transitions. Use [watchAsFlow] instead when the collector keeps
 * derived state — after a compaction resync this flow silently skips the gap.
 */
fun Client.watchEventsAsFlow(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  resilience: WatchResilience = WatchResilience.DEFAULT,
  capacity: Int = Channel.UNLIMITED,
): Flow<WatchEvent> =
  watchAsFlow(keyName, option, resilience, resyncWith = null, capacity = capacity)
    .transform { element ->
      if (element is WatchFlowEvent.Response) {
        element.response.events.forEach { emit(it) }
      }
    }
