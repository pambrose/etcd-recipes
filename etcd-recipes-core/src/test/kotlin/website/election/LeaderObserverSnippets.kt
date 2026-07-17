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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package website.election

import io.etcd.jetcd.Client
import io.etcd.recipes.coroutines.LeadershipEvent
import io.etcd.recipes.coroutines.leadershipAsFlow
import io.etcd.recipes.election.LeaderListener
import io.etcd.recipes.election.LeaderObserver
import io.etcd.recipes.election.withLeaderObserver
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun basicObserver(client: Client) {
  // --8<-- [start:basic]
  LeaderObserver(client, "/election/reports").use { observer ->
    // start() seeds the snapshot with a read of the leader key, so currentLeader
    // is already populated when it returns. This node never runs for election.
    observer.start()
    logger.info { "Leader is ${observer.currentLeader ?: "(vacant)"}" }
  }
  // --8<-- [end:basic]
}

fun observerWithListener(client: Client) {
  // --8<-- [start:listener]
  val listener =
    object : LeaderListener {
      override fun takeLeadership(leaderName: String) {
        logger.info { "$leaderName took leadership" }
      }

      override fun relinquishLeadership() {
        logger.info { "The election is vacant" }
      }

      override fun onError(e: Throwable) {
        logger.error(e) { "Leader observation failed" }
      }
    }

  LeaderObserver(client, "/election/reports").use { observer ->
    // Register before start(): start() emits no synthetic callback for the leader
    // it seeds, so only subsequent changes reach the listener.
    observer.addListener(listener)
    observer.start()
  }
  // --8<-- [end:listener]
}

fun scopedObserver(client: Client) {
  // --8<-- [start:scoped]
  // withLeaderObserver starts the observer and closes it on exit.
  val leader = withLeaderObserver(client, "/election/reports") { currentLeader }
  logger.info { "Leader at that moment: ${leader ?: "(vacant)"}" }
  // --8<-- [end:scoped]
}

suspend fun observeLeadershipFlow(client: Client) {
  // --8<-- [start:flow]
  client.leadershipAsFlow("/election/reports")
    .collect { event ->
      when (event) {
        // The current leader is emitted first, so a late collector is not blind.
        is LeadershipEvent.Elected -> logger.info { "Leader is now ${event.leaderName}" }

        is LeadershipEvent.Vacated -> logger.info { "The election is vacant" }

        is LeadershipEvent.WatchFailed -> logger.error(event.cause) { "Observation stopped" }
      }
    }
  // --8<-- [end:flow]
}
