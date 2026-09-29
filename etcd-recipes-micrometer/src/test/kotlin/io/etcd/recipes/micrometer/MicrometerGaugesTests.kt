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
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.queue.AbstractQueue
import io.etcd.recipes.queue.DistributedQueue
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The live-state gauge binders. Each recipe is mocked so its accessor returns a fixed value,
 * and the registered gauge is asserted to report it — the gauge supplier polls the accessor on
 * each read, so the binding is what's under test, not etcd.
 */
class MicrometerGaugesTests : StringSpec() {
  init {
    "bindQueueDepth reports the queue size, read with the probe budget" {
      val registry = SimpleMeterRegistry()
      registry.bindQueueDepth(mockk<AbstractQueue> { every { size(RpcResilience.PROBE) } returns 5 })
      registry.find("etcd.queue.depth").gauge().shouldNotBeNull().value() shouldBe 5.0
    }

    "bindCacheSize reports the path-children-cache entry count" {
      val registry = SimpleMeterRegistry()
      registry.bindCacheSize(
        mockk<PathChildrenCache> {
        every { currentData } returns [mockk(), mockk(), mockk()]
      },
      )
      registry.find("etcd.cache.entries").gauge().shouldNotBeNull().value() shouldBe 3.0
    }

    "bindServiceCacheSize reports the service-cache instance count" {
      val registry = SimpleMeterRegistry()
      registry.bindServiceCacheSize(mockk<ServiceCache> { every { instances } returns [mockk(), mockk()] })
      registry.find("etcd.cache.entries").gauge().shouldNotBeNull().value() shouldBe 2.0
    }

    "bindAvailablePermits reports the available permit count, read with the probe budget" {
      val registry = SimpleMeterRegistry()
      registry.bindAvailablePermits(
        mockk<DistributedSemaphore> { every { availablePermits(RpcResilience.PROBE) } returns 4 },
      )
      registry.find("etcd.semaphore.available").gauge().shouldNotBeNull().value() shouldBe 4.0
    }

    "RPC-backed gauges answer promptly while etcd is unreachable" {
      connectToEtcd(listOf("http://127.0.0.1:1")).use { client ->
        val registry = SimpleMeterRegistry()
        val depth = registry.bindQueueDepth(DistributedQueue(client, "/micrometer/queue"))
        val permits = registry.bindAvailablePermits(DistributedSemaphore(client, "/micrometer/semaphore", 2))
        for (gauge in listOf(depth, permits)) {
          var value: Double? = null
          val done = CountDownLatch(1)
          thread(isDaemon = true) {
            value = gauge.value()
            done.countDown()
          }
          withClue("${gauge.id.name} held the scrape") { done.await(10, TimeUnit.SECONDS) shouldBe true }
          value!!.isNaN() shouldBe true
        }
      }
    }

    "bindLeadership reports 1 while the latch holds leadership" {
      val registry = SimpleMeterRegistry()
      registry.bindLeadership(mockk<LeaderLatch> { every { hasLeadership } returns true })
      registry.find("etcd.election.leader").gauge().shouldNotBeNull().value() shouldBe 1.0
    }
  }
}
