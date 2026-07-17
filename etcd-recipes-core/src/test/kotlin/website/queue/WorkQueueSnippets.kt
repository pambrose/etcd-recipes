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
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.asString
import io.etcd.recipes.coroutines.awaitAck
import io.etcd.recipes.coroutines.awaitEnqueue
import io.etcd.recipes.coroutines.awaitReceive
import io.etcd.recipes.queue.DistributedWorkQueue
import io.etcd.recipes.queue.WorkQueueConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun basicWorkQueue(client: Client) {
  // --8<-- [start:basic]
  DistributedWorkQueue(client, "/workqueue/jobs").use { queue ->
    queue.enqueue("job-1")

    // The item is claimed, not deleted: it survives in etcd until it is acked, so
    // a crash here redelivers it instead of losing it.
    val item = queue.receive()
    logger.info { "Processing ${item.value.asString} (attempt ${item.attempt})" }

    // ack() completes the item. false means the claim was already lost, so the
    // work may have been redone elsewhere — do not treat it as success.
    if (!item.ack()) {
      logger.warn { "Lost the claim for ${item.id}" }
    }
  }
  // --8<-- [end:basic]
}

fun configuredWorkQueue(client: Client) {
  // --8<-- [start:config]
  val config =
    WorkQueueConfig(
      // How long after a consumer *dies* its claims become reclaimable. A live
      // consumer renews its lease, so this does not bound processing time.
      visibilityTimeoutSecs = 30,
      // Delivery attempts before an item is dead-lettered instead of redelivered.
      maxDeliveries = 5,
      // How often each consumer sweeps for orphaned claims and matured delays.
      sweepInterval = 30.seconds,
    )

  DistributedWorkQueue(client, "/workqueue/jobs", config, clientId = "worker-1").use { queue ->
    logger.info { "Consumer ${queue.clientId} ready" }
  }
  // --8<-- [end:config]
}

fun workQueueConsumer(client: Client) {
  // --8<-- [start:consumer]
  DistributedWorkQueue(client, "/workqueue/jobs").use { queue ->
    while (true) {
      val item = queue.receive(30.seconds) ?: break

      // Delivery is at-least-once, so this body must be idempotent: a redelivery
      // is indistinguishable from a first delivery except for item.attempt.
      try {
        handle(item.value.asString)
        item.ack()
      } catch (e: IllegalStateException) {
        // Hand it back now, attempts preserved, rather than making every other
        // consumer wait out the visibility timeout.
        logger.warn(e) { "Attempt ${item.attempt} of ${item.id} failed; requeueing" }
        item.requeue()
      }
    }
  }
  // --8<-- [end:consumer]
}

private fun handle(payload: String) {
  logger.info { "Handling $payload" }
}

fun workQueueDeadLetters(client: Client) {
  // --8<-- [start:dead-letters]
  DistributedWorkQueue(client, "/workqueue/jobs").use { queue ->
    // Items that exhausted maxDeliveries land here instead of being redelivered
    // forever. Nothing drains this automatically — it is an operator surface.
    for (dead in queue.deadLetters()) {
      logger.warn { "Dead letter ${dead.id} after ${dead.attempts} attempts: ${dead.value.asString}" }
    }

    // Once the underlying bug is fixed, replay one with a fresh attempt count...
    queue.requeueDeadLetter("1700000000000-abc")

    // ...or drop a genuinely poisonous payload for good. Both return false when
    // no such dead letter exists.
    queue.purgeDeadLetter("1700000000001-def")
  }
  // --8<-- [end:dead-letters]
}

fun workQueueDelayed(client: Client) {
  // --8<-- [start:delayed]
  DistributedWorkQueue(client, "/workqueue/jobs").use { queue ->
    // Invisible until it matures, then it takes its place among the ready items.
    // Maturity is judged against client clocks, so producer/consumer skew shifts
    // delivery by the skew.
    queue.enqueue("send-reminder", 5.seconds)

    // Deliverable immediately.
    queue.enqueue("send-welcome")
  }
  // --8<-- [end:delayed]
}

fun workQueueLeaseListener(client: Client) {
  // --8<-- [start:lease-listener]
  DistributedWorkQueue(client, "/workqueue/jobs").use { queue ->
    // Every claim marker this consumer makes hangs off one lease. If that lease
    // expires, all its outstanding claims become reclaimable and their acks fail.
    queue.addLeaseListener { event ->
      when (event) {
        is LeaseEvent.Expired -> logger.warn { "Lease expired; claims are reclaimable" }
        is LeaseEvent.Restored -> logger.info { "Lease healed: ${event.newLeaseId}" }
        is LeaseEvent.Failed -> logger.error { "Lease healing abandoned; claims will not hold" }
        is LeaseEvent.Suspended -> logger.debug { "Keep-alive hiccup; jetcd is retrying" }
      }
    }

    queue.receive(30.seconds)?.ack()
  }
  // --8<-- [end:lease-listener]
}

suspend fun workQueueCoroutines(client: Client) {
  // --8<-- [start:coroutines]
  DistributedWorkQueue(client, "/workqueue/jobs").use { queue ->
    queue.awaitEnqueue("job-1")

    // Suspends until an item can be claimed; cancelling leaves no orphan claim.
    val item = queue.awaitReceive()
    logger.info { "Processing ${item.value.asString}" }
    item.awaitAck()
  }
  // --8<-- [end:coroutines]
}
