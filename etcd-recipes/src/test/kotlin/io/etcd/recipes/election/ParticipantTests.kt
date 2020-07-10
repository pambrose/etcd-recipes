/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.election

import com.github.pambrose.common.concurrent.thread
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import kotlin.time.seconds

class ParticipantTests {
  val path = "/election/${javaClass.simpleName}"

  @Test
  fun participantTest() {
    val count = 20
    val startedLatch = CountDownLatch(count)
    val finishedLatch = CountDownLatch(count)
    val holdLatch = CountDownLatch(1)
    val participantCounts: MutableList<Int> = synchronizedList(mutableListOf())
    val leaderNames: MutableList<String> = synchronizedList(mutableListOf())

    connectToEtcd(urls) { client ->
      blockingThreads(count) {
        thread(finishedLatch) {
          withLeaderSelector(client,
                             path,
                             object : LeaderSelectorListenerAdapter() {
                               override fun takeLeadership(selector: LeaderSelector) {
                                 val pause = 2.seconds
                                 logger.debug { "${selector.clientId} elected leader for $pause" }
                                 sleep(pause)

                                 // Wait until participation count has been taken
                                 holdLatch.await()
                                 participantCounts += LeaderSelector.getParticipants(client, path).size
                                 leaderNames += selector.clientId
                               }
                             },
                             clientId = "Thread$it") {
            start()
            startedLatch.countDown()
            waitOnLeadershipComplete()
          }
        }
      }

      startedLatch.await()

      // Wait for participants to register
      sleep(3.seconds)
      var particpants = LeaderSelector.getParticipants(client, path)
      logger.debug { "Found ${particpants.size} participants" }
      particpants.size shouldBeEqualTo count

      holdLatch.countDown()

      finishedLatch.await()

      sleep(5.seconds)

      particpants = LeaderSelector.getParticipants(client, path)
      logger.debug { "Found ${particpants.size} participants" }
      particpants.size shouldBeEqualTo 0

      // Compare participant counts
      logger.debug { "participantCounts = $participantCounts" }
      participantCounts.size shouldBeEqualTo count
      participantCounts shouldBeEqualTo (count downTo 1).toList()

      // Compare leader names
      logger.debug { "leaderNames = $leaderNames" }
      leaderNames.sorted() shouldBeEqualTo List(count) { "Thread$it" }.sorted()
    }
  }

  companion object : KLogging()
}