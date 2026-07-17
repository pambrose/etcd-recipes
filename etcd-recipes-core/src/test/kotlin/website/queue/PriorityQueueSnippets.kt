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
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.TypedDistributedPriorityQueue
import io.etcd.recipes.queue.withDistributedPriorityQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun basicPriorityQueue(client: Client) {
  // --8<-- [start:basic]
  // minimumWaitTime has no default on the constructor — pass ZERO for no pacing.
  DistributedPriorityQueue(client, "/queues/jobs", minimumWaitTime = Duration.ZERO).use { queue ->
    queue.enqueue("nightly-report", 200)
    queue.enqueue("page-oncall", 1)

    // Lower number wins: "page-oncall" comes out first, regardless of enqueue order.
    logger.info { "Dequeued ${queue.dequeue().asString}" }
  }
  // --8<-- [end:basic]
}

fun scopedPriorityQueue(client: Client) {
  // --8<-- [start:scoped]
  // Here minimumWaitTime does default to zero.
  withDistributedPriorityQueue(client, "/queues/jobs") {
    enqueue("page-oncall", 1)
    enqueue("nightly-report", 200)
  }

  val job = withDistributedPriorityQueue(client, "/queues/jobs") { poll(5.seconds) }
  logger.info { "Dequeued ${job?.asString}" }
  // --8<-- [end:scoped]
}

fun priorityTypes(client: Client) {
  // --8<-- [start:priority-types]
  withDistributedPriorityQueue(client, "/queues/jobs") {
    // Int priorities are range-checked against 0..65535 and throw
    // IllegalArgumentException outside it, rather than silently wrapping mod 65536
    // and filing the entry in the wrong bucket.
    enqueue("page-oncall", 1)

    // UShort is the underlying priority type, so this overload needs no check.
    enqueue("nightly-report", 200u)
  }
  // --8<-- [end:priority-types]
}

fun pacedPriorityQueue(client: Client) {
  // --8<-- [start:minimum-wait-time]
  // Each enqueue on this instance sleeps until at least 20ms has passed since the
  // previous one, thinning out same-priority CAS contention on the sequence key.
  // It paces this instance only — it is not a cluster-wide rate limit.
  withDistributedPriorityQueue(client, "/queues/jobs", minimumWaitTime = 20.milliseconds) {
    repeat(10) { i -> enqueue("job-$i", 1) }
  }
  // --8<-- [end:minimum-wait-time]
}

fun typedPriorityQueue(client: Client) {
  // --8<-- [start:typed]
  TypedDistributedPriorityQueue(client, "/queues/jobs", jsonCodec<Order>()).use { queue ->
    queue.enqueue(Order(1, "widget"), priority = 1)
    val order: Order = queue.dequeue()
    logger.info { "Dequeued $order" }
  }
  // --8<-- [end:typed]
}
