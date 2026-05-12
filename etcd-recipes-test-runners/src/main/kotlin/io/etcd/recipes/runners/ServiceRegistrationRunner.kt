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

import com.pambrose.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.discovery.withServiceDiscovery
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class ServiceRegistrationPayload(
  val serviceName: String,
  val instanceId: String,
)

object ServiceRegistrationRunner : RecipeRunner {
  override val recipe: String = "discovery"
  override val role: String = "register"

  override fun run(
    client: Client,
    testId: String,
    participantId: String,
    args: Args,
  ): ParticipantResult {
    val servicePath = args.require("service-path")
    val serviceName = args.require("service-name")
    val shutdownKey = args.require("shutdown-key")
    val maxWaitMs = args.longOr("max-wait-ms", 60_000L)

    val instance =
      ServiceInstance(
        name = serviceName,
        jsonPayload = """{"participant":"$participantId"}""",
      )

    return withServiceDiscovery(client, servicePath) {
      registerService(instance)

      // Block until the orchestrator (running on the host JVM) signals shutdown
      // by writing any value to shutdownKey. Polling is fine here — service
      // discovery tests don't measure latency, and a watch would complicate
      // the runner without buying anything.
      val deadline = System.currentTimeMillis() + maxWaitMs
      while (System.currentTimeMillis() < deadline && !client.isKeyPresent(shutdownKey)) {
        sleep(200.milliseconds)
      }

      val signalled = client.isKeyPresent(shutdownKey)
      unregisterService(instance)

      ParticipantResult(
        testId = testId,
        participantId = participantId,
        role = "$recipe/$role",
        success = signalled,
        payloadJson =
          encodePayload(
            ServiceRegistrationPayload(
              serviceName = serviceName,
              instanceId = instance.id,
            ),
          ),
      )
    }
  }
}
