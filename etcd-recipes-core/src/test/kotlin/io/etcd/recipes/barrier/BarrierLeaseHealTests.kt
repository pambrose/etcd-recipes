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

package io.etcd.recipes.barrier

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Drives lease loss for real by revoking a barrier's lease out-of-band (etcd
 * exposes every key's lease id), which is what a partition longer than the TTL
 * produces. The lease-bound keys must be re-established by the self-healing
 * keep-alive instead of silently vanishing.
 */
class BarrierLeaseHealTests : StringSpec() {
  private val path = "/barrier/${javaClass.simpleName}"

  private fun revokeLeaseOf(
    client: io.etcd.jetcd.Client,
    keyPath: String,
  ) {
    val leaseId = client.getResponse(keyPath).kvs.first().lease
    (leaseId > 0L) shouldBe true
    client.leaseClient.revoke(leaseId).get()
  }

  init {
    "a set barrier re-arms after its lease is lost" {
      connectToEtcd(urls) { client ->
        val barrierPath = "$path/rearm"
        DistributedBarrier(client, barrierPath).use { barrier ->
          barrier.setBarrier() shouldBe true
          barrier.isBarrierSet() shouldBe true

          // Simulate lease expiry: the barrier key vanishes...
          revokeLeaseOf(client, barrierPath)
          pollUntil(10.seconds) { !client.isKeyPresent(barrierPath) } shouldBe true

          // ...and the self-healing lease re-arms it for future waiters
          pollUntil(20.seconds) { barrier.isBarrierSet() } shouldBe true

          barrier.removeBarrier() shouldBe true
          barrier.isBarrierSet() shouldBe false
        }
      }
    }

    "a counted-barrier waiter's key heals while the waiter is parked" {
      connectToEtcd(urls) { client ->
        val barrierPath = "$path/counted"
        val waitingPath = "$barrierPath/waiting"
        DistributedBarrierWithCount(client, barrierPath, memberCount = 2).use { barrier ->
          val released = AtomicReference<Boolean?>(null)
          val waiter = thread { released.store(barrier.waitOnBarrier(1.minutes)) }

          pollUntil(20.seconds) { client.getChildrenKeys(waitingPath).size == 1 } shouldBe true
          val waiterKey = client.getChildrenKeys(waitingPath).first()

          revokeLeaseOf(client, waiterKey)
          pollUntil(10.seconds) { !client.isKeyPresent(waiterKey) } shouldBe true

          // The waiter's registration heals, so the barrier can still trip
          pollUntil(20.seconds) { client.isKeyPresent(waiterKey) } shouldBe true

          // Second member arrives; both released
          DistributedBarrierWithCount(client, barrierPath, memberCount = 2).use { second ->
            second.waitOnBarrier(1.minutes) shouldBe true
          }
          pollUntil(20.seconds) { released.load() == true } shouldBe true
          waiter.join(5_000)
        }
      }
    }
  }
}
