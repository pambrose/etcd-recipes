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
import io.etcd.recipes.queue.DistributedWorkQueue
import io.etcd.recipes.queue.WorkQueueConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates at-least-once consumption: the flaky consumer "crashes" (skips the
 * ack) on its first delivery, and the item comes back with an incremented attempt
 * until it succeeds — or would dead-letter after maxDeliveries.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]

  connectToEtcd(urls) { client ->
    val config = WorkQueueConfig(visibilityTimeoutSecs = 2, maxDeliveries = 3)
    DistributedWorkQueue(client, "/examples/workqueue", config).use { queue ->
      queue.enqueue("flaky-job")

      var done = false
      do {
        val item = queue.receive(30.seconds)
        when {
          item == null -> {
            done = true
          }

          item.attempt == 1 -> {
            logger.info { "Received ${item.value.asString} (attempt 1); simulating a failure — requeueing" }
            item.requeue()
          }

          else -> {
            logger.info { "Received ${item.value.asString} (attempt ${item.attempt}); ack=${item.ack()}" }
            done = true
          }
        }
      } while (!done)

      logger.info { "Dead letters: ${queue.deadLetters().map { it.value.asString }}" }
    }
  }
}
