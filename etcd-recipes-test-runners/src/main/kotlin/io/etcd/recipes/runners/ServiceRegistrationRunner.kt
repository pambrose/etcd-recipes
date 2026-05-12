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

      // Block until the orchestrator (host JVM) writes shutdownKey.
      val deadline = System.currentTimeMillis() + maxWaitMs
      var signalled = false
      while (System.currentTimeMillis() < deadline) {
        if (client.isKeyPresent(shutdownKey)) {
          signalled = true
          break
        }
        sleep(200.milliseconds)
      }

      unregisterService(instance)

      result(
        testId = testId,
        participantId = participantId,
        success = signalled,
        payload =
          ServiceRegistrationPayload(
            serviceName = serviceName,
            instanceId = instance.id,
          ),
      )
    }
  }
}
