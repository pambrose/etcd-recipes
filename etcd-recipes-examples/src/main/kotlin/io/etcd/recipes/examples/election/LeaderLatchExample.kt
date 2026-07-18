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

import com.pambrose.common.concurrent.thread
import com.pambrose.common.util.random
import com.pambrose.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.election.LeaderLatch
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * Five nodes each run a [LeaderLatch] and hold leadership until they close it — the
 * "acquire and hold until shutdown" shape. Exactly one is leader at a time; when the
 * leader closes, a successor takes over.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val electionPath = "/election/latch-example"
  val count = 5
  val done = CountDownLatch(count)

  repeat(count) {
    thread(done) {
      connectToEtcd(urls) { client ->
        LeaderLatch(client, electionPath, clientId = "Node$it").use { latch ->
          latch.start()
          latch.await() // block until this node holds leadership
          val heldFor = 3.random().seconds
          logger.info { "${latch.clientId} acquired leadership, holding for $heldFor" }
          sleep(heldFor)
          logger.info { "${latch.clientId} releasing leadership" }
          // leaving use{} closes the latch → releases candidacy → a successor leads
        }
      }
    }
  }
  done.await()
  logger.info { "All nodes finished" }
}
