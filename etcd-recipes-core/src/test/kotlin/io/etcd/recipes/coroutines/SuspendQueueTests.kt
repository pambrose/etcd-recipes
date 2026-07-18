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

package io.etcd.recipes.coroutines

import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.urls
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.DistributedQueue
import io.etcd.recipes.queue.DistributedWorkQueue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Suspend surface for the queues: FIFO delivery, bounded receive, priority order,
 * work-queue claim/ack, delayed maturity, and cancellation consuming nothing.
 */
class SuspendQueueTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  init {
    "receive returns enqueued values in order" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedQueue(client, "$base/fifo").use { queue ->
          queue.awaitEnqueue("a")
          queue.awaitEnqueue("b")
          queue.awaitEnqueue("c")
          queue.receive().asString shouldBe "a"
          queue.receive().asString shouldBe "b"
          queue.receive().asString shouldBe "c"
        }
      }
    }

    "receive with timeout returns null on an empty queue" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedQueue(client, "$base/empty").use { queue ->
          queue.receive(2.seconds) shouldBe null
        }
      }
    }

    "cancelling a parked receive consumes nothing" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedQueue(client, "$base/park").use { queue ->
          coroutineScope {
            val parked = launch { queue.receive() }
            delay(1_000) // let it park on the empty queue
            parked.cancelAndJoin()
          }
          queue.awaitEnqueue("later")
          queue.receive(10.seconds).shouldNotBeNull().asString shouldBe "later"
        }
      }
    }

    "awaitEnqueueAll preserves order in one transaction" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedQueue(client, "$base/batch").use { queue ->
          queue.awaitEnqueueAll(["x".asByteSequence, "y".asByteSequence, "z".asByteSequence])
          queue.receive().asString shouldBe "x"
          queue.receive().asString shouldBe "y"
          queue.receive().asString shouldBe "z"
        }
      }
    }

    "priority queue delivers by priority through the suspend twins" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedPriorityQueue(client, "$base/priority", minimumWaitTime = 0.milliseconds).use { queue ->
          queue.awaitEnqueue("low", 10)
          queue.awaitEnqueue("high", 1)
          queue.awaitEnqueue("mid", 5u.toUShort())
          queue.receive().asString shouldBe "high"
          queue.receive().asString shouldBe "mid"
          queue.receive().asString shouldBe "low"
        }
      }
    }

    "work queue claim and ack lifecycle" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedWorkQueue(client, "$base/work").use { wq ->
          wq.awaitEnqueue("job1")
          val item = wq.awaitReceive()
          item.value.asString shouldBe "job1"
          item.awaitAck() shouldBe true
          wq.awaitTryReceive() shouldBe null
        }
      }
    }

    "cancelling a parked awaitReceive leaves no claim behind" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedWorkQueue(client, "$base/work-cancel").use { wq ->
          coroutineScope {
            val parked = launch { wq.awaitReceive() }
            delay(1_000)
            parked.cancelAndJoin()
          }
          wq.awaitEnqueue("survivor")
          val item = wq.awaitReceive(10.seconds).shouldNotBeNull()
          item.value.asString shouldBe "survivor"
          item.awaitAck() shouldBe true
        }
      }
    }

    "delayed enqueue matures through the suspend twins" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedWorkQueue(client, "$base/delayed").use { wq ->
          wq.awaitEnqueue("slow".asByteSequence, 2.seconds)
          wq.awaitTryReceive() shouldBe null // not matured yet
          val item = wq.awaitReceive(15.seconds).shouldNotBeNull()
          item.value.asString shouldBe "slow"
          item.awaitAck() shouldBe true
        }
      }
    }
  }
}
