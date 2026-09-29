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

package io.etcd.recipes.queue

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.LeaseResilience
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.awaitOrFail
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Work-queue lifecycle and delivery guarantees:
 *
 * - `maxDeliveries` caps redelivery through `requeue()`, not only through a crash;
 * - a receive stops retrying a dead consumer lease once healing is abandoned, and
 *   `close()` wakes a parked receive, which fails instead of claiming after close;
 * - a close that races the consumer lease's creation leaves no claim behind;
 * - the empty-queue wait is anchored at the revision the queue was seen empty;
 * - reclaim-sweep failures reach `exceptions`, once per failing streak;
 * - a stale `WorkItem` can't ack or requeue a later claim of the same item.
 */
class WorkQueueLifecycleTests : StringSpec() {
  private val base = "/workqueue/${javaClass.simpleName}"

  private class Outcome {
    val done = CountDownLatch(1)

    @Volatile var value: Any? = null

    @Volatile var error: Throwable? = null

    fun finishedWithin(seconds: Long) = done.await(seconds, TimeUnit.SECONDS)
  }

  private fun inBackground(block: () -> Any?): Outcome =
    Outcome().also { outcome ->
      thread(isDaemon = true) {
        try {
          outcome.value = block()
        } catch (e: Throwable) {
          outcome.error = e
        } finally {
          outcome.done.countDown()
        }
      }
    }

  private fun revokeClaimLease(
    client: Client,
    queuePath: String,
  ) {
    val claimKey = client.getChildrenKeys("$queuePath/claims").first()
    client.leaseClient.revoke(client.getResponse(claimKey).kvs.first().lease).get()
  }

  init {
    "an item requeued past maxDeliveries is dead-lettered instead of redelivered" {
      connectToEtcd(urls) { client ->
        val path = "$base/requeue-cap"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path, WorkQueueConfig(maxDeliveries = 2)).use { queue ->
          queue.enqueue("poison")
          repeat(2) { n ->
            val item = queue.receive(10.seconds).shouldNotBeNull()
            item.attempt shouldBe n + 1
            item.requeue() shouldBe true
          }
          withClue("a requeued item was delivered past maxDeliveries") { queue.receive(2.seconds).shouldBeNull() }
          val dead = queue.deadLetters()
          dead shouldHaveSize 1
          dead.first().value.asString shouldBe "poison"
          dead.first().attempts shouldBe 2
        }
        client.deleteChildren(path)
      }
    }

    "a receive stops retrying a dead consumer lease once healing is abandoned" {
      connectToEtcd(urls) { client ->
        val path = "$base/heal-abandoned"
        client.deleteChildren(path)
        val resilience = ResilienceConfig(lease = LeaseResilience.DISABLED)
        DistributedWorkQueue(client, path, WorkQueueConfig(visibilityTimeoutSecs = 2), resilience).use { queue ->
          val abandoned = CountDownLatch(1)
          queue.addLeaseListener { event -> if (event is LeaseEvent.Failed) abandoned.countDown() }
          queue.enqueue("first")
          queue.receive(10.seconds).shouldNotBeNull() // creates the consumer lease
          revokeClaimLease(client, path)
          abandoned.awaitOrFail(30.seconds, "lease heal abandonment")

          queue.enqueue("second")
          val outcome = inBackground { queue.receive(3.seconds) }
          withClue("receive(3s) kept retrying the dead lease past its timeout") {
            outcome.finishedWithin(15) shouldBe true
          }
          withClue("value=${outcome.value} error=${outcome.error}") {
            (outcome.error is EtcdRecipeRuntimeException) shouldBe true
          }
        }
        client.deleteChildren(path)
      }
    }

    "close() wakes a parked receive that holds claims, which then fails" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/close-with-claims"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val queue = DistributedWorkQueue(client, path, WorkQueueConfig(sweepInterval = 1.seconds))
        queue.enqueue("held")
        queue.receive(10.seconds).shouldNotBeNull() // claimed, never acked
        val parked = CountDownLatch(1)
        client.afterWatch.store { parked.countDown() }
        val outcome = inBackground { queue.receive() }
        parked.awaitOrFail(10.seconds, "the parked receive")
        queue.close()
        withClue("close() did not wake the parked receive") { outcome.finishedWithin(5) shouldBe true }
        withClue("value=${outcome.value} error=${outcome.error}") {
          (outcome.error is EtcdRecipeRuntimeException) shouldBe true
        }
        etcd.deleteChildren(path)
      }
    }

    "close() wakes a parked receive that never claimed, and nothing is claimed after close" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/close-before-claim"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val queue = DistributedWorkQueue(client, path)
        val parked = CountDownLatch(1)
        client.afterWatch.store { parked.countDown() }
        val outcome = inBackground { queue.receive() }
        parked.awaitOrFail(10.seconds, "the parked receive")
        queue.close()
        withClue("close() did not wake the parked receive") { outcome.finishedWithin(5) shouldBe true }
        (outcome.error is EtcdRecipeRuntimeException) shouldBe true

        DistributedWorkQueue(etcd, path).use { other ->
          other.enqueue("after-close")
          other.receive(10.seconds).shouldNotBeNull().value.asString shouldBe "after-close"
        }
        etcd.deleteChildren(path)
      }
    }

    "a close() racing the consumer lease's creation leaves no claim behind" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/close-during-lease-grant"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val queue = DistributedWorkQueue(client, path)
        queue.enqueue("racing")
        client.beforeLeaseGrant.store { thread { queue.close() }.join() }
        shouldThrow<EtcdRecipeRuntimeException> { queue.tryReceive() }

        DistributedWorkQueue(etcd, path).use { other ->
          withClue("the item was claimed by the closed consumer") {
            other.tryReceive().shouldNotBeNull().value.asString shouldBe "racing"
          }
        }
        etcd.deleteChildren(path)
      }
    }

    "the empty-queue wait is anchored at the revision the queue was seen empty" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/anchored-wait"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        DistributedWorkQueue(client, path).use { queue ->
          val before = etcd.getResponse(path).header.revision
          queue.receive(1.seconds).shouldBeNull()
          val option = client.watchOptions.first { (key, _) -> key.startsWith("$path/items") }.second
          withClue("the empty-queue watch is not revision-anchored") { option.revision shouldBeGreaterThan before }
        }
        etcd.deleteChildren(path)
      }
    }

    "a failing reclaim sweep is reported once per failing streak" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/sweep-failures"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        DistributedWorkQueue(client, path, WorkQueueConfig(sweepInterval = 100.milliseconds)).use { queue ->
          queue.enqueue("x")
          queue.receive(10.seconds).shouldNotBeNull().ack() shouldBe true // starts the sweeper
          val failing = AtomicBoolean(true)

          fun failNextGet() {
            client.beforeGet.store {
              if (failing.load()) {
                failNextGet()
                throw StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("denied"))
              }
            }
          }
          failNextGet()
          pollUntil(10.seconds) { queue.exceptions.isNotEmpty() } shouldBe true
          Thread.sleep(1_000) // ~10 more failing sweeps
          withClue("every failing sweep was recorded") { queue.exceptions.size shouldBe 1 }

          failing.store(false)
          Thread.sleep(500) // a successful sweep ends the streak
          failing.store(true)
          failNextGet()
          pollUntil(10.seconds) { queue.exceptions.size == 2 } shouldBe true
          failing.store(false)
        }
        etcd.deleteChildren(path)
      }
    }

    "a stale WorkItem can't ack or requeue a later claim of the same item" {
      connectToEtcd(urls) { client ->
        val path = "$base/stale-item"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("job")
          val first = queue.receive(10.seconds).shouldNotBeNull()
          // The claim lapses (as after a lease expiry) and this same instance re-claims the item
          client.deleteKey("$path/claims/${first.id}")
          val second = queue.receive(10.seconds).shouldNotBeNull()
          second.id shouldBe first.id
          second.attempt shouldBe 2
          withClue("a stale item acked the new claim") { first.ack() shouldBe false }
          withClue("a stale item requeued the new claim") { first.requeue() shouldBe false }
          second.ack() shouldBe true
        }
        client.deleteChildren(path)
      }
    }
  }
}
