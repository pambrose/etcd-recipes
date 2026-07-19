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
import io.etcd.recipes.counter.withDistributedAtomicLong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ContainerCounterTest :
  StringSpec({
    "concurrent incrementer containers produce a consistent final counter value" {
      assumeContainerMode()

      val incrementerCount = 5
      val incrementsEach = 20
      val expectedFinal = (incrementerCount * incrementsEach).toLong()
      val testId = newTestId("counter")
      val counterPath = "/counters/$testId"
      val hostUrls = [EtcdContainerNetwork.hostEndpoint()]

      val containers =
        (1..incrementerCount).map { i ->
          Participant.newContainer(
            recipe = "counter",
            role = "incrementer",
            testId = testId,
            participantId = i.toString(),
            extraArgs =
              mapOf(
                "counter-path" to counterPath,
                "increment-count" to incrementsEach.toString(),
              ),
          )
        }

      useContainers(containers) {
        connectToEtcd(hostUrls) { client ->
          val results = client.awaitResults(testId, expectedCount = incrementerCount, timeout = 60.seconds)
          results.size shouldBe incrementerCount
          results.values.forEach { it.success shouldBe true }

          val finalValue = withDistributedAtomicLong(client, counterPath) { get() }
          finalValue shouldBe expectedFinal
        }
      }
    }
  })
