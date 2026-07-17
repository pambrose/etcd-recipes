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

package website.micrometer

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdRecipes
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.lock.withLock
import io.etcd.recipes.micrometer.MicrometerEtcdMetrics
import io.etcd.recipes.micrometer.bindAvailablePermits
import io.etcd.recipes.micrometer.bindLeadership
import io.etcd.recipes.micrometer.bindQueueDepth
import io.etcd.recipes.queue.DistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

private val logger = KotlinLogging.logger {}

fun installMetrics(
  client: Client,
  registry: MeterRegistry,
) {
  // --8<-- [start:install]
  // withMetrics() returns a copy that routes every RPC, watch, and lease funnel — plus the
  // recipe-level seams (lock waits, elections, queue ops, cache syncs) — to the registry.
  val resilience = ResilienceConfig.DEFAULT.withMetrics(MicrometerEtcdMetrics(registry))

  DistributedMutex(client, "/locks/orders", resilience = resilience).use { mutex ->
    mutex.withLock { logger.info { "Timed as etcd.lock.wait and etcd.lock.hold" } }
  }
  // --8<-- [end:install]
}

fun instrumentEverything(
  client: Client,
  registry: MeterRegistry,
): EtcdRecipes {
  // --8<-- [start:factory]
  // Hand the instrumented config to the EtcdRecipes factory once and every recipe it
  // builds reports, rather than remembering to pass `resilience` at each construction.
  val resilience = ResilienceConfig.DEFAULT.withMetrics(MicrometerEtcdMetrics(registry))
  return EtcdRecipes(client, resilience)
  // --8<-- [end:factory]
}

fun bindGauges(
  client: Client,
  registry: MeterRegistry,
) {
  // --8<-- [start:gauges]
  // Gauges are pull-based: bind one to an instance you already hold and it polls that
  // instance on every scrape. Micrometer keeps only a weak reference, so binding a gauge
  // does not keep the recipe alive.
  DistributedQueue(client, "/queues/orders").use { queue ->
    registry.bindQueueDepth(queue, Tags.of("queue", "orders"))
  }

  LeaderLatch(client, "/election/orders").use { latch ->
    latch.start()
    // 1.0 while this instance is the leader, 0.0 otherwise. An in-memory read; no RPC.
    registry.bindLeadership(latch, Tags.of("election", "orders"))
  }
  // --8<-- [end:gauges]
}

fun bindPollingGauge(
  client: Client,
  registry: MeterRegistry,
) {
  // --8<-- [start:polling-gauge]
  // Mind the ones that cost an RPC. availablePermits() (like AbstractQueue.size) issues a
  // range-count against etcd, so this gauge hits the cluster on EVERY scrape.
  DistributedSemaphore(client, "/semaphores/pool", 5).use { semaphore ->
    registry.bindAvailablePermits(semaphore, Tags.of("pool", "workers"))
  }
  // --8<-- [end:polling-gauge]
}
