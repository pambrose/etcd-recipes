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

import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Delayed delivery on the work queue (product idea #4, phase C): items enqueued
 * with a delay stay invisible until they mature, then flow through the normal
 * claim/ack lifecycle. Maturity rides on client clocks — skew tolerance is at
 * visibility-timeout scale, documented on the API.
 */
class WorkQueueDelayTests : StringSpec() {
  private val base = "/workqueue/${javaClass.simpleName}"

  init {
    "a delayed item is invisible before its delay and delivered after it" {
      connectToEtcd(urls) { client ->
        val path = "$base/basic"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          val start = TimeSource.Monotonic.markNow()
          queue.enqueue("later", 3.seconds)

          queue.tryReceive().shouldBeNull() // not yet mature

          val item = queue.receive(30.seconds)
          item.shouldNotBeNull()
          item.value.asString shouldBe "later"
          item.attempt shouldBe 1
          (start.elapsedNow() >= 2.5.seconds) shouldBe true
          item.ack() shouldBe true
        }
      }
    }

    "delayed items deliver in ready-time order regardless of enqueue order" {
      connectToEtcd(urls) { client ->
        val path = "$base/order"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("slow", 5.seconds)
          queue.enqueue("fast", 2.seconds)

          queue.receive(30.seconds)!!.also { it.value.asString shouldBe "fast" }.ack()
          queue.receive(30.seconds)!!.also { it.value.asString shouldBe "slow" }.ack()
        }
      }
    }

    "immediate items are not blocked behind delayed ones" {
      connectToEtcd(urls) { client ->
        val path = "$base/mixed"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("patient", 4.seconds)
          queue.enqueue("now")

          val start = TimeSource.Monotonic.markNow()
          queue.receive(10.seconds)!!.also { it.value.asString shouldBe "now" }.ack()
          (start.elapsedNow() < 3.seconds) shouldBe true

          queue.receive(30.seconds)!!.also { it.value.asString shouldBe "patient" }.ack()
        }
      }
    }

    "a bounded receive returns null when the only item matures after the timeout" {
      connectToEtcd(urls) { client ->
        val path = "$base/still-delayed"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("distant", 30.seconds)
          val start = TimeSource.Monotonic.markNow()
          queue.receive(2.seconds).shouldBeNull()
          (start.elapsedNow() < 15.seconds) shouldBe true
        }
      }
    }

    "a zero or negative delay enqueues immediately" {
      connectToEtcd(urls) { client ->
        val path = "$base/zero"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("instant", 0.seconds)
          queue.receive(10.seconds)!!.also { it.value.asString shouldBe "instant" }.ack()
        }
      }
    }
  }
}
