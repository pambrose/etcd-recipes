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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class ReportLeaderTests {

  val path = "/election/${javaClass.simpleName}"

  @Test
  fun reportLeaderTest() {
    val count = 25
    val takeLeadershiptCounter = AtomicInteger(0)
    val relinquishLeadershiptCounter = AtomicInteger(0)

    val executor = Executors.newSingleThreadExecutor()
    LeaderSelector.reportLeader(
      urls,
      path,
      object : LeaderListener {
        override fun takeLeadership(leaderName: String) {
          logger.debug { "$leaderName elected leader" }
          takeLeadershiptCounter.incrementAndGet()
        }

        override fun relinquishLeadership() {
          relinquishLeadershiptCounter.incrementAndGet()
        }
      },
      executor
    )

    sleep(5.seconds)

    blockingThreads(count) {
      connectToEtcd(urls) { client ->
        withLeaderSelector(
          client,
          path,
          object : LeaderSelectorListenerAdapter() {
            override fun takeLeadership(selector: LeaderSelector) {
              val pause = 2.random().seconds
              logger.debug { "${selector.clientId} elected leader for $pause" }
              sleep(pause)
            }
          },
          clientId = "Thread$it"
        ) {
          start()
          waitOnLeadershipComplete()
        }
      }
    }

    // This requires a pause because reportLeader() needs to get notified (via a watcher) of the change in leadership
    sleep(10.seconds)

    takeLeadershiptCounter.get() shouldBeEqualTo count
    relinquishLeadershiptCounter.get() shouldBeEqualTo count

    executor.shutdown()
  }

  companion object : KLogging()
}