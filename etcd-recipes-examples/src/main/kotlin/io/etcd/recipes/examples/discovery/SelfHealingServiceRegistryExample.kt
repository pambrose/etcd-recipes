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

package io.etcd.recipes.examples.discovery

import com.pambrose.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.discovery.ServiceRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates self-healing service registration: kill and restart the local etcd
 * (`./etcd.sh`) — or stop it for longer than the TTL — while this runs. The
 * instance registration expires with its lease and is automatically re-registered
 * when the healer re-grants it; every lease event is printed.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val servicePath = "/services/self-healing-example"

  connectToEtcd(urls) { client ->
    ServiceRegistry(client, servicePath, leaseTtlSecs = 2).use { registry ->
      registry.addLeaseListener { event -> logger.info { "Lease event: $event" } }
      registry.addConnectionStateListener { new, prev -> logger.info { "Connection state: $prev -> $new" } }

      val instance = ServiceInstance("ExampleService", """{"port": 8080}""")
      registry.registerService(instance)
      logger.info { "Registered ${instance.name}/${instance.id}" }

      repeat(120) {
        sleep(2.seconds)
        logger.info { "Still running; exceptions so far: ${registry.exceptions.size}" }
      }
    }
  }
}
