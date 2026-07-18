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

package io.etcd.recipes.examples.coroutines

import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.coroutines.awaitEnqueue
import io.etcd.recipes.coroutines.receive
import io.etcd.recipes.queue.DistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Structured-concurrency producer/consumers over a distributed queue: one
 * producer enqueues without blocking a thread, several consumers suspend on
 * [receive], and cancellation of a parked consumer consumes nothing.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val queuePath = "/examples/coroutines/queue"

  runBlocking {
    connectToEtcd(urls).use { client ->
      DistributedQueue(client, queuePath).use { queue ->
        coroutineScope {
          // Three consumers, each suspending on receive() with a bounded wait
          repeat(3) { id ->
            launch(Dispatchers.Default) {
              while (true) {
                val item = withTimeoutOrNull(3.seconds) { queue.receive() } ?: break
                logger.info { "Consumer $id got ${item.asString}" }
              }
            }
          }
          // Producer feeds ten jobs, then lets the consumers drain and time out
          repeat(10) { n ->
            queue.awaitEnqueue("job-$n")
            delay(100)
          }
        }
      }
    }
  }
}
