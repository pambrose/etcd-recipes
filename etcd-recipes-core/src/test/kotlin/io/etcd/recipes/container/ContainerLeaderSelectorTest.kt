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
import io.etcd.recipes.runners.ElectionParticipantPayload
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ContainerLeaderSelectorTest :
  StringSpec({
    "every candidate container takes and relinquishes leadership exactly once" {
      assumeContainerMode()

      val count = 5
      val testId = newTestId("election")
      val electionPath = "/election/$testId"
      val hostUrls = listOf(EtcdContainerNetwork.hostEndpoint())

      val containers =
        (1..count).map { i ->
          Participant.newContainer(
            recipe = "election",
            role = "participant",
            testId = testId,
            participantId = i.toString(),
            extraArgs = mapOf("election-path" to electionPath),
          )
        }

      useContainers(containers) {
        connectToEtcd(hostUrls) { client ->
          val results = client.awaitResults(testId, expectedCount = count, timeout = 60.seconds)
          results.size shouldBe count
          results.values.forEach { result ->
            result.success shouldBe true
            val payload = result.payload<ElectionParticipantPayload>()
            payload.tookLeadership shouldBe true
            payload.relinquished shouldBe true
          }
        }
      }
    }
  })
