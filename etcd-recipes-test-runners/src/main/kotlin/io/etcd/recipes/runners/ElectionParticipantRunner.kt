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
import io.etcd.jetcd.op.CmpTarget
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.election.withLeaderSelector
import kotlinx.serialization.Serializable

/**
 * One candidate's election outcome. [overlapped] is true when its term began while another
 * candidate's term was still running: two leaders at once.
 */
@Serializable
data class ElectionParticipantPayload(
  val tookLeadership: Boolean,
  val relinquished: Boolean,
  val clientId: String,
  val overlapped: Boolean = false,
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

    // Each term claims this marker for its duration, so a term that starts while another is
    // still running finds it taken. A sibling of the election path, not one of its keys.
    val activeLeaderKey = "$electionPath-active"
    var tookLeadership = false
    var relinquished = false
    var overlapped = false

    withLeaderSelector(
      client = client,
      electionPath = electionPath,
      takeLeadershipBlock = { _: LeaderSelector ->
        tookLeadership = true
        val claimed =
          client.transaction {
            If(activeLeaderKey.doesNotExist)
            Then(activeLeaderKey setTo clientId)
          }.isSucceeded
        overlapped = !claimed
        // A term long enough that another leader's overlapping it would be seen
        Thread.sleep(TERM_MILLIS)
        if (claimed)
          client.transaction {
            If(equalTo(activeLeaderKey, CmpTarget.value(clientId.asByteSequence)))
            Then(deleteOp(activeLeaderKey))
          }
      },
      relinquishLeadershipBlock = { _: LeaderSelector -> relinquished = true },
      clientId = clientId,
    ) {
      start()
      waitOnLeadershipComplete()
    }

    return result(
      testId = testId,
      participantId = participantId,
      success = tookLeadership && relinquished,
      payload =
        ElectionParticipantPayload(
          tookLeadership = tookLeadership,
          relinquished = relinquished,
          clientId = clientId,
          overlapped = overlapped,
        ),
    )
  }

  private const val TERM_MILLIS = 300L
}
