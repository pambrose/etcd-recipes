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
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.discovery.withServiceDiscovery
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ContainerServiceDiscoveryTest :
  StringSpec({
    "3 registration containers each appear in service discovery" {
      assumeContainerMode()

      val count = 3
      val testId = newTestId("discovery")
      val servicePath = "/discovery/$testId"
      val serviceName = "example-service"
      val shutdownKey = "/discovery-shutdown/$testId"
      val hostUrls = [EtcdContainerNetwork.hostEndpoint()]

      val containers =
        (1..count).map { i ->
          Participant.newContainer(
            recipe = "discovery",
            role = "register",
            testId = testId,
            participantId = i.toString(),
            extraArgs =
              mapOf(
                "service-path" to servicePath,
                "service-name" to serviceName,
                "shutdown-key" to shutdownKey,
                "max-wait-ms" to "30000",
              ),
          )
        }

      useContainers(containers) {
        connectToEtcd(hostUrls) { client ->
          withServiceDiscovery(client, servicePath) {
            // Registration happens after the runner connects to etcd, so polling beats a fixed sleep.
            val allVisible =
              pollUntil(15.seconds) {
                queryForInstances(serviceName).size == count
              }
            allVisible shouldBe true
          }

          // Signal participants to unregister and exit.
          client.putValue(shutdownKey, "stop")

          val results = client.awaitResults(testId, expectedCount = count, timeout = 30.seconds)
          results.size shouldBe count
          results.values.forEach { it.success shouldBe true }
        }
      }
    }
  })
