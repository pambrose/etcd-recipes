/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.util.random
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.Collections.synchronizedList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class ThreadedLeaderSelectorTests {
  val path = "/election/${javaClass.simpleName}"
  val count = 10

  @Test
  fun threadedElection1Test() {
    val takeLeadershiptCounter = AtomicInteger(0)
    val relinquishLeadershiptCounter = AtomicInteger(0)

    blockingThreads(count) {
      val takeAction =
        { selector: LeaderSelector ->
          val pause = 3.random().seconds
          logger.debug { "${selector.clientId} elected leader for $pause" }
          takeLeadershiptCounter.incrementAndGet()
          sleep(pause)
        }

      val relinquishAction =
        { selector: LeaderSelector ->
          relinquishLeadershiptCounter.incrementAndGet()
          logger.debug { "${selector.clientId} relinquished leadership" }
        }

      connectToEtcd(urls) { client ->
        withLeaderSelector(client, path, takeAction, relinquishAction, clientId = "Thread$it") {
          start()
          waitOnLeadershipComplete()
        }
      }
    }

    takeLeadershiptCounter.get() shouldBeEqualTo count
    relinquishLeadershiptCounter.get() shouldBeEqualTo count
  }

  @Test
  fun threadedElection2Test() {
    val takeLeadershiptCounter = AtomicInteger(0)
    val relinquishLeadershiptCounter = AtomicInteger(0)
    val electionList: MutableList<LeaderSelector> = synchronizedList(mutableListOf())

    val takeAction =
      { selector: LeaderSelector ->
        val pause = 3.random().seconds
        logger.debug { "${selector.clientId} elected leader for $pause" }
        takeLeadershiptCounter.incrementAndGet()
        sleep(pause)
      }

    val relinquishAction =
      { selector: LeaderSelector ->
        relinquishLeadershiptCounter.incrementAndGet()
        logger.debug { "${selector.clientId} relinquished leadership" }
      }

    connectToEtcd(urls) { client ->
      blockingThreads(count) {
        logger.debug { "Creating Thread$it" }

        val election = LeaderSelector(client, path, takeAction, relinquishAction, clientId = "Thread$it")
        electionList += election
        election.start()
      }

      logger.debug { "Size = ${electionList.size}" }

      electionList
        .onEach { it.waitOnLeadershipComplete() }
        .forEach { it.close() }
    }

    takeLeadershiptCounter.get() shouldBeEqualTo count
    relinquishLeadershiptCounter.get() shouldBeEqualTo count
  }

  companion object : KLogging()
}