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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.queue.TypedDistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Enqueue and dequeue typed values with a [TypedDistributedQueue]: a `jsonCodec<Order>()` marshals
 * each [Order] so callers never touch raw bytes.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val path = "/queue/typed-example"

  connectToEtcd(urls) { client ->
    TypedDistributedQueue(client, path, jsonCodec<Order>()).use { queue ->
      queue.enqueue(Order(1, "widget"))
      queue.enqueueAll([Order(2, "gadget"), Order(3, "gizmo")])

      repeat(3) {
        val order: Order = queue.dequeue()
        logger.info { "Dequeued: $order" }
      }
    }
  }
}
