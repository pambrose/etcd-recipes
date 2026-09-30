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

import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.etcd.recipes.election.ElectionPaths
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** A change in who holds leadership at an election path, from an observer's view. */
sealed interface LeadershipEvent {
  /** [leaderName] became (or is) the leader. */
  data class Elected(
    val leaderName: String,
  ) : LeadershipEvent

  /** Leadership was relinquished and no successor has been observed yet. */
  data object Vacated : LeadershipEvent

  /** The leadership watch was abandoned; observation has stopped. */
  data class WatchFailed(
    val cause: Throwable?,
  ) : LeadershipEvent
}

/**
 * Observes who holds leadership at [electionPath] as a [Flow] of [LeadershipEvent]:
 * the current leader is emitted first, then `Elected` on each hand-off and `Vacated`
 * when the leader steps down. Backed by the resilient watcher, so a compaction or
 * stream death re-reads the leader; an abandoned watch emits `WatchFailed`.
 *
 * This is an observer, not a participant — use [io.etcd.recipes.election.LeaderSelector]
 * (with the suspending `awaitStart` / `awaitLeadershipComplete`) to run for election.
 * Collection subscribes a watcher and cancellation closes it.
 */
fun Client.leadershipAsFlow(
  electionPath: String,
  resilience: WatchResilience = WatchResilience.DEFAULT,
  capacity: Int = Channel.UNLIMITED,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Flow<LeadershipEvent> =
  callbackFlow {
    val leaderKey = ElectionPaths.leaderKey(electionPath)

    // Read the current leader, then watch from just past that read, so a hand-off during
    // setup is still delivered.
    val seed = awaitGetResponse(leaderKey, rpc = rpc)
    val recoveryListener = leadershipRecovery(this@leadershipAsFlow, electionPath, leaderKey, rpc)

    val watcher =
      watcher(
        leaderKey,
        watchOption { withRevision(seed.header.revision + 1) },
        resilience,
        recoveryListener,
        resyncWith = null,
      ) { response ->
        response.events.forEach { event ->
          when (event.eventType) {
            PUT -> {
              trySendBlocking(LeadershipEvent.Elected(ElectionPaths.stripLeaderClientId(event.keyValue.value.asString)))
            }

            DELETE -> {
              trySendBlocking(LeadershipEvent.Vacated)
            }

            else -> {
              Unit
            }
          }
        }
      }
    // Emit the current leader first so a late collector is not left blind.
    send(leadershipOf(seed))
    awaitClose { watcher.close() }
  }.buffer(capacity)

private fun leadershipOf(response: GetResponse): LeadershipEvent =
  response.kvs.firstOrNull()?.value?.asString
    ?.let { LeadershipEvent.Elected(ElectionPaths.stripLeaderClientId(it)) }
    ?: LeadershipEvent.Vacated

// Re-reads the leader after a recovery that could have missed a hand-off (a resync, or a
// resume from "now"), and reports an abandoned watch. Either failure ends the flow: the
// observation can't be trusted any more, and no further event will come.
@Suppress("TooGenericExceptionCaught") // any failed re-read ends the observation
private fun ProducerScope<LeadershipEvent>.leadershipRecovery(
  client: Client,
  electionPath: String,
  leaderKey: String,
  rpc: RpcResilience,
): WatchRecoveryListener =
  WatchRecoveryListener { event ->
    when (event) {
      is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
        val gapPossible = event !is WatchRecoveryEvent.Resubscribed || event.resumeRevision == 0L
        if (gapPossible) {
          try {
            trySendBlocking(leadershipOf(client.getResponse(leaderKey, rpc = rpc)))
          } catch (e: Exception) {
            trySendBlocking(LeadershipEvent.WatchFailed(e))
            channel.close()
          }
        }
      }

      is WatchRecoveryEvent.Failed -> {
        trySendBlocking(
          LeadershipEvent.WatchFailed(
            event.cause ?: EtcdRecipeRuntimeException("Leadership watch on $electionPath abandoned"),
          ),
        )
        channel.close()
      }

      is WatchRecoveryEvent.Suspended -> {
        Unit
      }
    }
  }
