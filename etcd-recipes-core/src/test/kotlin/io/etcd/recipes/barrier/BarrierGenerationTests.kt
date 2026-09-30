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

import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * A counted barrier trips one generation at a time, and releases nobody until the trip is
 * committed:
 *
 * - `/ready` is deleted before the tripper leaves, the delete is retried on a transient
 *   failure, and a delete that can't be committed leaves the tripper parked and recorded;
 * - waiters register under their generation (`waiting/<ready createRevision>/…`) and count
 *   only it, so leftovers of an earlier round can't trip a later one.
 *
 * Also: `DistributedBarrier.setBarrier()` after `removeBarrier()` on the same instance sets
 * the barrier again.
 */
class BarrierGenerationTests : StringSpec() {
  private val base = "/barriers/${javaClass.simpleName}"

  // Fails the [client]'s transactions numbered [from] (1-based) onward, [times] of them.
  private fun failTxns(
    client: HookedClient,
    from: Int,
    times: Int = Int.MAX_VALUE,
  ) {
    val seen = AtomicInt(0)

    fun arm() {
      client.beforeTxn.store {
        val n = seen.incrementAndFetch()
        arm()
        if (n >= from && n - from < times) throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("injected"))
      }
    }
    arm()
  }

  init {
    "a release that fails once is retried, not reported as a failed wait" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/retried-release"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        failTxns(client, from = 3, times = 1) // the ready CAS, the waiting-key CAS, then the release
        DistributedBarrierWithCount(client, path, memberCount = 1).use { barrier ->
          withClue("a release that failed once failed the wait") { barrier.waitOnBarrier(10.seconds) shouldBe true }
          withClue("the release was never committed") { etcd.isKeyPresent("$path/ready") shouldBe false }
        }
        etcd.deleteChildren(path)
      }
    }

    "a release that can't be committed leaves the tripper parked, and says why" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/failed-release"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        failTxns(client, from = 3) // every release attempt fails
        DistributedBarrierWithCount(client, path, memberCount = 1).use { barrier ->
          withClue("the tripper left though the barrier still stands") {
            barrier.waitOnBarrier(3.seconds) shouldBe false
          }
          withClue("the failed release wasn't recorded") { barrier.exceptions.isNotEmpty() shouldBe true }
          etcd.isKeyPresent("$path/ready") shouldBe true
        }
        etcd.deleteChildren(path)
      }
    }

    "a waiter counts only its own generation's waiters" {
      connectToEtcd(urls) { client ->
        val path = "$base/generation"
        client.deleteChildren(path)
        client.putValue("$path/waiting/stale:abc", "left by an earlier round")
        DistributedBarrierWithCount(client, path, memberCount = 2).use { barrier ->
          val result = AtomicReference<Boolean?>(null)
          val waiter = thread(isDaemon = true) { result.store(barrier.waitOnBarrier(4.seconds)) }
          waiter.join(15_000)
          withClue("a lone waiter tripped the barrier with an earlier round's key") { result.load() shouldBe false }
        }
        client.deleteChildren(path)
      }
    }

    "waiterCount counts the current generation" {
      connectToEtcd(urls) { client ->
        val path = "$base/waiter-count"
        client.deleteChildren(path)
        client.putValue("$path/waiting/stale:abc", "left by an earlier round")
        DistributedBarrierWithCount(client, path, memberCount = 2).use { barrier ->
          val waiter = thread(isDaemon = true) { runCatching { barrier.waitOnBarrier(10.seconds) } }
          pollUntil(10.seconds) { client.isKeyPresent("$path/ready") } shouldBe true
          withClue("waiterCount included an earlier round's key") {
            pollUntil(5.seconds) { barrier.waiterCount == 1L } shouldBe true
            barrier.waiterCount shouldBe 1L
          }
          barrier.close()
          waiter.join(10_000)
        }
        client.deleteChildren(path)
      }
    }

    "parties looping on one path trip every round together" {
      connectToEtcd(urls) { client ->
        val path = "$base/looping"
        client.deleteChildren(path)
        val rounds = 5
        val results = CopyOnWriteArrayList<Boolean>()
        val loopers =
          (1..2).map { i ->
            thread(isDaemon = true) {
              DistributedBarrierWithCount(client, path, memberCount = 2, clientId = "looper-$i").use { barrier ->
                repeat(rounds) { results += barrier.waitOnBarrier(10.seconds) }
              }
            }
          }
        loopers.forEach { it.join(120_000) }
        withClue("a round tripped without both parties, or never tripped") {
          results shouldBe List(2 * rounds) { true }
        }
        client.deleteChildren(path)
      }
    }

    "setBarrier after removeBarrier on the same instance sets it again" {
      connectToEtcd(urls) { client ->
        val path = "$base/set-remove-set"
        client.deleteChildren(path)
        DistributedBarrier(client, path).use { barrier ->
          barrier.setBarrier() shouldBe true
          barrier.removeBarrier() shouldBe true
          withClue("the second setBarrier was declined") { barrier.setBarrier() shouldBe true }
          barrier.isBarrierSet() shouldBe true
          barrier.removeBarrier() shouldBe true
          barrier.isBarrierSet() shouldBe false
        }
        client.deleteChildren(path)
      }
    }
  }
}
