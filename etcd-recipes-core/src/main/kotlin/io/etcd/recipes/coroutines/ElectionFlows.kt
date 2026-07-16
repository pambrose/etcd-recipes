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
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.watcher
import io.etcd.recipes.election.ElectionPaths
import kotlinx.coroutines.channels.Channel
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
): Flow<LeadershipEvent> =
  callbackFlow {
    val leaderKey = ElectionPaths.leaderKey(electionPath)

    fun emitCurrentLeader() {
      val leader = getValue(leaderKey)?.asString?.let { ElectionPaths.stripLeaderClientId(it) }
      trySendBlocking(if (leader != null) LeadershipEvent.Elected(leader) else LeadershipEvent.Vacated)
    }

    // Emit the current leader first so a late collector is not left blind.
    emitCurrentLeader()

    val recoveryListener =
      WatchRecoveryListener { event ->
        when (event) {
          // A hand-off may have been missed while the stream was dead: re-read.
          is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
            emitCurrentLeader()
          }

          is WatchRecoveryEvent.Failed -> {
            trySendBlocking(
              LeadershipEvent.WatchFailed(
                event.cause ?: EtcdRecipeRuntimeException("Leadership watch on $electionPath abandoned"),
              ),
            )
          }

          is WatchRecoveryEvent.Suspended -> {
            Unit
          }
        }
      }

    val watcher =
      watcher(leaderKey, WatchOption.DEFAULT, resilience, recoveryListener, resyncWith = null) { response ->
        response.events.forEach { event ->
          when (event.eventType) {
            PUT -> {
              trySendBlocking(
                LeadershipEvent.Elected(ElectionPaths.stripLeaderClientId(event.keyValue.value.asString)),
              )
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
    awaitClose { watcher.close() }
  }.buffer(capacity)
