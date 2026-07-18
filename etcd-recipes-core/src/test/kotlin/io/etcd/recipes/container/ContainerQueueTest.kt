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

package io.etcd.recipes.container

import io.etcd.recipes.common.EtcdContainerNetwork
import io.etcd.recipes.common.Participant
import io.etcd.recipes.common.awaitResults
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.payload
import io.etcd.recipes.queue.withDistributedQueue
import io.etcd.recipes.runners.QueueConsumerPayload
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ContainerQueueTest :
  StringSpec({
    "5 consumer containers collectively dequeue every item the orchestrator produced" {
      assumeContainerMode()

      val consumerCount = 5
      val itemsPerConsumer = 20
      val total = consumerCount * itemsPerConsumer
      val testId = newTestId("queue")
      val queuePath = "/queues/$testId"
      val hostUrls = [EtcdContainerNetwork.hostEndpoint()]
      val produced = (1..total).map { "item-%04d".format(it) }

      connectToEtcd(hostUrls) { client ->
        // Enqueue before consumers start so they all see a fully-populated queue and
        // assertions don't depend on race-free producer/consumer overlap.
        withDistributedQueue(client, queuePath) { produced.forEach { enqueue(it) } }

        val containers =
          (1..consumerCount).map { i ->
            Participant.newContainer(
              recipe = "queue",
              role = "consumer",
              testId = testId,
              participantId = i.toString(),
              extraArgs =
                mapOf(
                  "queue-path" to queuePath,
                  "item-count" to itemsPerConsumer.toString(),
                ),
            )
          }

        useContainers(containers) {
          val results = client.awaitResults(testId, expectedCount = consumerCount, timeout = 60.seconds)
          results.size shouldBe consumerCount

          val dequeuedByEachConsumer = results.values.map { it.payload<QueueConsumerPayload>().items }
          dequeuedByEachConsumer.forEach { it.size shouldBe itemsPerConsumer }
          // Every produced item shows up exactly once across all consumers (no duplicates, no losses).
          dequeuedByEachConsumer.flatten().sorted() shouldBe produced.sorted()
          // Each consumer's items are in queue (FIFO) order — items are zero-padded ascending strings.
          dequeuedByEachConsumer.forEach { it shouldBe it.sorted() }
        }
      }
    }
  })
