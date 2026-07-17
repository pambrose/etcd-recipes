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

package website.discovery

import io.etcd.jetcd.Client
import io.etcd.recipes.discovery.ProviderStrategy
import io.etcd.recipes.discovery.RandomStrategy
import io.etcd.recipes.discovery.RoundRobinStrategy
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.discovery.ServiceProvider
import io.etcd.recipes.discovery.StickyStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

// Stands in for whatever RPC you make against the chosen instance.
private fun sendRequest(instance: ServiceInstance): Boolean = instance.enabled

fun basicProvider(client: Client) {
  // --8<-- [start:basic]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    discovery.serviceProvider("worker").use { provider ->
      // start() opens an owned, watch-backed ServiceCache, so every later read is
      // in-memory. It is one-shot: a second start() throws.
      provider.start()

      // RandomStrategy by default. Throws EtcdRecipeException when nothing is
      // available — no instances registered, or all of them ejected.
      val instance = provider.getInstance()
      logger.info { "Selected ${instance.address}:${instance.port}" }

      logger.info { "${provider.getAllInstances().size} instances registered" }
    }
  }
  // --8<-- [end:basic]
}

fun directProvider(client: Client) {
  // --8<-- [start:direct]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    discovery.serviceProvider("worker").use { provider ->
      // No start(): every read is a fresh range GET against etcd. Fine for a
      // one-off lookup, wrong for a hot path.
      val instance = provider.getInstance()
      logger.info { "Selected ${instance.address}:${instance.port}" }
    }
  }
  // --8<-- [end:direct]
}

fun roundRobinProvider(client: Client) {
  // --8<-- [start:round-robin]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    // A fresh RoundRobinStrategy for THIS provider: it carries a cursor, and
    // sharing one across providers interleaves their rotations.
    discovery.withServiceProvider("worker", RoundRobinStrategy()) {
      start()
      repeat(6) { logger.info { "-> ${getInstance().port}" } }
    }
  }
  // --8<-- [end:round-robin]
}

fun stickyProvider(client: Client) {
  // --8<-- [start:sticky]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    // Session affinity: keeps returning the same instance while it is still
    // registered, then delegates a fresh pick. Also stateful — one per provider.
    discovery.withServiceProvider("worker", StickyStrategy(RandomStrategy)) {
      start()
      logger.info { "Pinned to ${getInstance().port}" }
    }
  }
  // --8<-- [end:sticky]
}

fun customStrategy(client: Client) {
  // --8<-- [start:custom-strategy]
  // ProviderStrategy is a fun interface: return null when the list is empty.
  val leastLoaded = ProviderStrategy { instances -> instances.minByOrNull { it.port } }

  ServiceDiscovery(client, "/services/orders").use { discovery ->
    discovery.withServiceProvider("worker", leastLoaded) {
      start()
      logger.info { "Selected ${getInstance().port}" }
    }
  }
  // --8<-- [end:custom-strategy]
}

fun ejectFailingInstance(client: Client) {
  // --8<-- [start:note-error]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    discovery
      .serviceProvider(
        "worker",
        strategy = RoundRobinStrategy(),
        errorThreshold = 3,
        downPeriod = 30.seconds,
      ).use { provider ->
        provider.start()

        val instance = provider.getInstance()
        if (!sendRequest(instance)) {
          // Pass back the SAME instance, unmodified: ejection is keyed on the
          // instance's value, so a mutated copy silently records nothing useful.
          // After errorThreshold errors it drops out of selection for downPeriod,
          // then rejoins automatically.
          provider.noteError(instance)
        }
      }
  }
  // --8<-- [end:note-error]
}

fun standaloneProvider(client: Client) {
  // --8<-- [start:standalone]
  // Read side only, no ServiceDiscovery façade. Note the path is <servicePath>/names.
  ServiceProvider(client, "/services/orders/names", "worker", RoundRobinStrategy()).use { provider ->
    provider.start()
    logger.info { "Selected ${provider.getInstance().port}" }
  }
  // --8<-- [end:standalone]
}
