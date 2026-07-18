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

package website.queue

import io.etcd.jetcd.Client
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.coroutines.awaitEnqueue
import io.etcd.recipes.coroutines.receive
import io.etcd.recipes.queue.DistributedQueue
import io.etcd.recipes.queue.TypedDistributedQueue
import io.etcd.recipes.queue.withDistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@Serializable
data class Order(
  val id: Int,
  val item: String,
)

fun basicQueue(client: Client) {
  // --8<-- [start:basic]
  DistributedQueue(client, "/queues/orders").use { queue ->
    queue.enqueue("order-1")

    // Blocks until an item is available. Exactly one consumer across the cluster
    // wins each item: the take is a CAS delete guarded on the item's mod revision.
    val value = queue.dequeue()
    logger.info { "Dequeued ${value.asString}" }
  }
  // --8<-- [end:basic]
}

fun boundedTakes(client: Client) {
  // --8<-- [start:try-dequeue]
  DistributedQueue(client, "/queues/orders").use { queue ->
    // Non-blocking: null the moment the queue is found empty.
    val immediate = queue.tryDequeue()

    // Bounded: waits under a watcher, then gives up and returns null.
    val waited = queue.poll(5.seconds)

    logger.info { "tryDequeue=${immediate?.asString}, poll=${waited?.asString}" }
  }
  // --8<-- [end:try-dequeue]
}

fun batchEnqueue(client: Client) {
  // --8<-- [start:enqueue-all]
  DistributedQueue(client, "/queues/orders").use { queue ->
    // One transaction: either every value lands or none does. The keys embed the
    // argument index, so within the batch consumers see them in argument order.
    queue.enqueueAll(
      [
        "order-1".asByteSequence,
        "order-2".asByteSequence,
        "order-3".asByteSequence,
      ],
    )

    // size costs a range-count RPC on every read — it is not a cached counter.
    logger.info { "Depth: ${queue.size}" }
  }
  // --8<-- [end:enqueue-all]
}

fun scopedQueue(client: Client) {
  // --8<-- [start:scoped]
  // Builds the queue, runs the block with it as receiver, closes it on the way out.
  val value = withDistributedQueue(client, "/queues/orders") {
    enqueue("order-1")
    dequeue()
  }
  logger.info { "Dequeued ${value.asString}" }
  // --8<-- [end:scoped]
}

fun typedQueue(client: Client) {
  // --8<-- [start:typed]
  TypedDistributedQueue(client, "/queues/orders", jsonCodec<Order>()).use { queue ->
    queue.enqueue(Order(1, "widget"))
    queue.enqueueAll([Order(2, "gadget"), Order(3, "gizmo")])

    val order: Order = queue.dequeue()
    logger.info { "Dequeued $order" }

    // The typed wrapper is a Closeable decorator, not an EtcdConnector: the
    // connector API lives on the instance it wraps.
    logger.info { "Healthy: ${queue.untyped.isHealthy()}, exceptions: ${queue.untyped.exceptions.size}" }
  }
  // --8<-- [end:typed]
}

suspend fun queueCoroutines(client: Client) {
  // --8<-- [start:coroutines]
  DistributedQueue(client, "/queues/orders").use { queue ->
    queue.awaitEnqueue("order-1")

    // Suspends rather than parking a thread. Cancelling the wait consumes nothing:
    // the queue is left exactly as it was.
    val value = queue.receive()
    logger.info { "Received ${value.asString}" }
  }
  // --8<-- [end:coroutines]
}
