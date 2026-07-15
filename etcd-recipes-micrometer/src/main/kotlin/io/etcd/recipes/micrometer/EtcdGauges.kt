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

package io.etcd.recipes.micrometer

import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.queue.AbstractQueue
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

/*
 * Live-state gauges. Unlike the push [io.etcd.recipes.common.EtcdMetrics] SPI, a gauge polls its
 * source on every metrics scrape — so bind one to a specific recipe instance you already hold.
 * Micrometer keeps only a weak reference to the recipe, so a bound gauge does not keep it alive.
 * Binding several instances of the same gauge to one registry needs distinguishing [tags].
 */

/**
 * A gauge of [queue]'s current item count. NOTE: [AbstractQueue.size] issues a range-count RPC,
 * so this gauge polls etcd on **every scrape** — mind the load on a hot queue / frequent scrape.
 */
fun MeterRegistry.bindQueueDepth(
  queue: AbstractQueue,
  tags: Tags = Tags.empty(),
): Gauge = Gauge.builder("etcd.queue.depth", queue) { it.size.toDouble() }.tags(tags).register(this)

/** A gauge of [cache]'s live entry count (an in-memory read; no RPC). */
fun MeterRegistry.bindCacheSize(
  cache: PathChildrenCache,
  tags: Tags = Tags.empty(),
): Gauge = Gauge.builder("etcd.cache.entries", cache) { it.currentData.size.toDouble() }.tags(tags).register(this)

/** A gauge of [cache]'s live instance count (an in-memory read; no RPC). */
fun MeterRegistry.bindServiceCacheSize(
  cache: ServiceCache,
  tags: Tags = Tags.empty(),
): Gauge = Gauge.builder("etcd.cache.entries", cache) { it.instances.size.toDouble() }.tags(tags).register(this)

/**
 * A gauge of [semaphore]'s available permits. NOTE: [DistributedSemaphore.availablePermits] issues
 * a range-count RPC, so this gauge polls etcd on **every scrape**.
 */
fun MeterRegistry.bindAvailablePermits(
  semaphore: DistributedSemaphore,
  tags: Tags = Tags.empty(),
): Gauge =
  Gauge.builder("etcd.semaphore.available", semaphore) { it.availablePermits().toDouble() }.tags(tags).register(this)

/** A gauge that is 1.0 while [latch] holds leadership and 0.0 otherwise (an in-memory read; no RPC). */
fun MeterRegistry.bindLeadership(
  latch: LeaderLatch,
  tags: Tags = Tags.empty(),
): Gauge = Gauge.builder("etcd.election.leader", latch) { if (it.hasLeadership) 1.0 else 0.0 }.tags(tags).register(this)
