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
import io.etcd.recipes.barrier.withDistributedBarrier
import io.etcd.recipes.common.putValue
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class BarrierWaiterPayload(
  val timeoutObserved: Boolean,
  val unblocked: Boolean,
  val unblockTimestampMs: Long,
)

object BarrierWaiterRunner : RecipeRunner {
  override val recipe: String = "barrier"
  override val role: String = "waiter"

  override fun run(
    client: Client,
    testId: String,
    participantId: String,
    args: Args,
  ): ParticipantResult {
    val barrierPath = args.require("barrier-path")
    val initialTimeoutMs = args.longOr("initial-timeout-ms", 1_000L)

    // waitOnMissingBarriers=false: if a slow-starting container reaches its second wait
    // after the orchestrator has already removed the barrier, return immediately rather
    // than hanging on a watcher that will never fire (the etcd key is already gone).
    return withDistributedBarrier(client, barrierPath, waitOnMissingBarriers = false) {
      // The orchestrator waits for every ready key before removing the barrier — closes
      // the race where a slow-starting container would see an already-cleared barrier.
      client.putValue("/barrier-ready/$testId/$participantId", "1")
      val timedOut = !waitOnBarrier(initialTimeoutMs.milliseconds)
      val unblocked = waitOnBarrier()

      result(
        testId = testId,
        participantId = participantId,
        success = timedOut && unblocked,
        payload =
          BarrierWaiterPayload(
            timeoutObserved = timedOut,
            unblocked = unblocked,
            unblockTimestampMs = System.currentTimeMillis(),
          ),
      )
    }
  }
}
