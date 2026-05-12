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

package io.etcd.recipes.runners

import io.etcd.jetcd.Client
import io.etcd.recipes.common.asString
import io.etcd.recipes.queue.withDistributedQueue
import kotlinx.serialization.Serializable

@Serializable
data class QueueConsumerPayload(
  val items: List<String>,
)

object QueueConsumerRunner : RecipeRunner {
  override val recipe: String = "queue"
  override val role: String = "consumer"

  override fun run(
    client: Client,
    testId: String,
    participantId: String,
    args: Args,
  ): ParticipantResult {
    val queuePath = args.require("queue-path")
    val itemCount = args.int("item-count")

    val items =
      withDistributedQueue(client, queuePath) {
        List(itemCount) { dequeue().asString }
      }

    return ParticipantResult(
      testId = testId,
      participantId = participantId,
      role = "$recipe/$role",
      success = items.size == itemCount,
      payloadJson = encodePayload(QueueConsumerPayload(items = items)),
    )
  }
}
