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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Demonstrates delayed delivery: the reminder enqueued with a 5-second delay stays
 * invisible until it matures, while the immediate item is delivered right away.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]

  connectToEtcd(urls) { client ->
    DistributedWorkQueue(client, "/examples/delayed").use { queue ->
      val start = TimeSource.Monotonic.markNow()
      queue.enqueue("send-reminder", 5.seconds)
      queue.enqueue("send-welcome")

      repeat(2) {
        val item = queue.receive()
        logger.info { "Received ${item.value.asString} after ${start.elapsedNow()}" }
        item.ack()
      }
    }
  }
}
