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

import io.etcd.recipes.barrier.DistributedBarrier
import io.etcd.recipes.common.EtcdContainerNetwork
import io.etcd.recipes.common.Participant
import io.etcd.recipes.common.awaitResults
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.payload
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.runners.BarrierWaiterPayload
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ContainerBarrierTest :
  StringSpec({
    "5 waiter containers all time out then unblock when barrier is removed" {
      assumeContainerMode()

      val count = 5
      val testId = newTestId("barrier")
      val barrierPath = "/barriers/$testId"
      val hostUrls = listOf(EtcdContainerNetwork.hostEndpoint())

      val containers =
        (1..count).map { i ->
          Participant.newContainer(
            recipe = "barrier",
            role = "waiter",
            testId = testId,
            participantId = i.toString(),
            extraArgs =
              mapOf(
                "barrier-path" to barrierPath,
                "initial-timeout-ms" to "1000",
              ),
          )
        }

      connectToEtcd(hostUrls) { client ->
        // Hold the DistributedBarrier open for the lifetime of the test: closing it
        // stops the lease keep-alive, and etcd auto-deletes the barrier key once the
        // lease TTL expires — which would race with slow container startup.
        DistributedBarrier(client, barrierPath).use { barrier ->
          barrier.setBarrier() shouldBe true

          useContainers(containers) {
            // Wait for every participant to write its ready key — guarantees they have
            // entered the first waitOnBarrier() while the barrier is still set, closing
            // the race where a slow container would see an already-cleared barrier.
            val allReady =
              pollUntil(30.seconds) {
                client.getChildrenKeys("/barrier-ready/$testId").size == count
              }
            allReady shouldBe true

            // Hold the barrier long enough for every participant's bounded first wait
            // (initial-timeout-ms = 1s) to time out before we clear it.
            Thread.sleep(2_000)

            barrier.removeBarrier() shouldBe true

            val results = client.awaitResults(testId, expectedCount = count, timeout = 30.seconds)
            results.size shouldBe count
            results.values.forEach { result ->
              result.success shouldBe true
              val payload = result.payload<BarrierWaiterPayload>()
              payload.timeoutObserved shouldBe true
              payload.unblocked shouldBe true
            }
          }
        }
      }
    }
  })
