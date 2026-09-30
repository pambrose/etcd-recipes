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

package io.etcd.recipes.examples.election

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.election.LeaderSelector.Companion.getParticipants
import io.etcd.recipes.election.withLeaderSelector
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val electionPath = "/election/threaded"
  val count = 5
  val latch = CountDownLatch(count)

  repeat(count) {
    thread {
      try {
        val takeLeadershipAction =
          { selector: LeaderSelector ->
            logger.info { "${selector.clientId} elected leader" }
            val pause = Random.nextInt(3).seconds
            Thread.sleep(pause.inWholeMilliseconds)
            logger.info { "${selector.clientId} surrendering after $pause" }
          }

        val relinquishLeadershipAction =
          { selector: LeaderSelector ->
            logger.info { "${selector.clientId} relinquished leadership" }
          }

        connectToEtcd(urls) { client ->
          withLeaderSelector(
            client,
            electionPath,
            takeLeadershipAction,
            relinquishLeadershipAction,
            clientId = "Thread$it",
          ) {
            start()
            waitOnLeadershipComplete()
          }
        }
      } finally {
        latch.countDown()
      }
    }
  }

  connectToEtcd(urls) { client ->
    while (latch.count > 0) {
      logger.info { "Participants: ${getParticipants(client, electionPath)}" }
      Thread.sleep(1_000)
    }
  }

  latch.await()
}
