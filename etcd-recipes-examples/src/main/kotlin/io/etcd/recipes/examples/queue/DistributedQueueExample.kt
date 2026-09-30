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

package io.etcd.recipes.examples.queue

import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.queue.withDistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val queuePath = "/queue/example"
  val iterCount = 50
  val threadCount = 5

  connectToEtcd(urls) { client ->

    logger.info {"Count: ${client.getChildCount(queuePath)}"}

    // Enqueue some data prior to dequeues
    withDistributedQueue(client, queuePath) {
      repeat(iterCount) { i -> enqueue("Before value $i") }
    }

    val latch = CountDownLatch(threadCount)
    repeat(threadCount) { sub ->
      thread {
        try {
          connectToEtcd(urls) { client ->
            withDistributedQueue(client, queuePath) {
              repeat((iterCount / threadCount) * 2) { logger.info {"Thread#: $sub Value: ${dequeue().asString}"} }
            }
          }
        } finally {
          latch.countDown()
        }
      }
    }

    Thread.sleep(2_000)

    // Now enqueue some data with dequeues waiting
    withDistributedQueue(client, queuePath) {
      repeat(iterCount) { i -> enqueue("After value $i") }
    }

    latch.await()

    logger.info {"Count: ${client.getChildCount(queuePath)}"}
  }
}
