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
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.election.withLeaderSelector
import kotlinx.serialization.Serializable

@Serializable
data class ElectionParticipantPayload(
  val tookLeadership: Boolean,
  val relinquished: Boolean,
  val clientId: String,
)

object ElectionParticipantRunner : RecipeRunner {
  override val recipe: String = "election"
  override val role: String = "participant"

  override fun run(
    client: Client,
    testId: String,
    participantId: String,
    args: Args,
  ): ParticipantResult {
    val electionPath = args.require("election-path")
    val clientId = "participant-$participantId"

    var tookLeadership = false
    var relinquished = false

    withLeaderSelector(
      client = client,
      electionPath = electionPath,
      takeLeadershipBlock = { _: LeaderSelector -> tookLeadership = true },
      relinquishLeadershipBlock = { _: LeaderSelector -> relinquished = true },
      clientId = clientId,
    ) {
      start()
      waitOnLeadershipComplete()
    }

    return ParticipantResult(
      testId = testId,
      participantId = participantId,
      role = "$recipe/$role",
      success = tookLeadership && relinquished,
      payloadJson =
        encodePayload(
          ElectionParticipantPayload(
            tookLeadership = tookLeadership,
            relinquished = relinquished,
            clientId = clientId,
          ),
        ),
    )
  }
}
