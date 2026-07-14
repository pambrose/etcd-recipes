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
import io.etcd.recipes.discovery.RoundRobinStrategy
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.discovery.withServiceDiscovery
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Client-side load balancing: register three instances of a service, then hand them out
 * round-robin from a watch-backed [io.etcd.recipes.discovery.ServiceProvider]. One
 * instance is marked failing via `noteError` and is skipped until its down window elapses.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")
  val servicePath = "/services/provider-example"
  val serviceName = "worker"

  connectToEtcd(urls) { client ->
    withServiceDiscovery(client, servicePath) {
      for (i in 1..3) registerService(ServiceInstance(serviceName, IntPayload(i).toJson()))

      // errorThreshold=1 so a single noteError ejects; short down window for the demo.
      withServiceProvider(serviceName, RoundRobinStrategy(), errorThreshold = 1, downPeriod = 5.seconds) {
        start()
        while (getAllInstances().size < 3) sleep(200.milliseconds)

        logger.info { "Round-robin across all three instances:" }
        repeat(6) { logger.info { "  -> ${getInstance().jsonPayload}" } }

        val failing = getInstance()
        logger.info { "Marking ${failing.jsonPayload} as failing" }
        noteError(failing)

        logger.info { "It is now skipped:" }
        repeat(6) { logger.info { "  -> ${getInstance().jsonPayload}" } }

        sleep(6.seconds)
        logger.info { "After the down window it is back in rotation:" }
        repeat(6) { logger.info { "  -> ${getInstance().jsonPayload}" } }
      }
    }
  }
}
