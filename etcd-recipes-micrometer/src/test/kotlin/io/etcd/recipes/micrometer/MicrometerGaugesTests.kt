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

package io.etcd.recipes.micrometer

import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.queue.AbstractQueue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk

/**
 * The live-state gauge binders. Each recipe is mocked so its accessor returns a fixed value,
 * and the registered gauge is asserted to report it — the gauge supplier polls the accessor on
 * each read, so the binding is what's under test, not etcd.
 */
class MicrometerGaugesTests : StringSpec() {
  init {
    "bindQueueDepth reports the queue size" {
      val registry = SimpleMeterRegistry()
      registry.bindQueueDepth(mockk<AbstractQueue> { every { size } returns 5 })
      registry.find("etcd.queue.depth").gauge().shouldNotBeNull().value() shouldBe 5.0
    }

    "bindCacheSize reports the path-children-cache entry count" {
      val registry = SimpleMeterRegistry()
      registry.bindCacheSize(
        mockk<PathChildrenCache> {
        every { currentData } returns listOf(mockk(), mockk(), mockk())
      },
      )
      registry.find("etcd.cache.entries").gauge().shouldNotBeNull().value() shouldBe 3.0
    }

    "bindServiceCacheSize reports the service-cache instance count" {
      val registry = SimpleMeterRegistry()
      registry.bindServiceCacheSize(mockk<ServiceCache> { every { instances } returns listOf(mockk(), mockk()) })
      registry.find("etcd.cache.entries").gauge().shouldNotBeNull().value() shouldBe 2.0
    }

    "bindAvailablePermits reports the available permit count" {
      val registry = SimpleMeterRegistry()
      registry.bindAvailablePermits(mockk<DistributedSemaphore> { every { availablePermits() } returns 4 })
      registry.find("etcd.semaphore.available").gauge().shouldNotBeNull().value() shouldBe 4.0
    }

    "bindLeadership reports 1 while the latch holds leadership" {
      val registry = SimpleMeterRegistry()
      registry.bindLeadership(mockk<LeaderLatch> { every { hasLeadership } returns true })
      registry.find("etcd.election.leader").gauge().shouldNotBeNull().value() shouldBe 1.0
    }
  }
}
