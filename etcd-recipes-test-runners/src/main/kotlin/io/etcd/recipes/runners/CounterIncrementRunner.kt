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
import io.etcd.recipes.counter.withDistributedAtomicLong
import kotlinx.serialization.Serializable

@Serializable
data class CounterIncrementPayload(
  val incrementsApplied: Int,
  val lastObservedValue: Long,
)

object CounterIncrementRunner : RecipeRunner {
  override val recipe: String = "counter"
  override val role: String = "incrementer"

  override fun run(
    client: Client,
    testId: String,
    participantId: String,
    args: Args,
  ): ParticipantResult {
    val counterPath = args.require("counter-path")
    val incrementCount = args.int("increment-count")

    var last = 0L
    withDistributedAtomicLong(client, counterPath) {
      start()
      repeat(incrementCount) { last = increment() }
    }

    return ParticipantResult(
      testId = testId,
      participantId = participantId,
      role = "$recipe/$role",
      success = true,
      payloadJson =
        encodePayload(
          CounterIncrementPayload(
            incrementsApplied = incrementCount,
            lastObservedValue = last,
          ),
        ),
    )
  }
}
