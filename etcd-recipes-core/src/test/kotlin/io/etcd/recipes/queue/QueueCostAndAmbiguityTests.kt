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

import io.etcd.jetcd.options.GetOption
import io.etcd.recipes.common.EtcdMetrics
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Queue ordering, cost, and ambiguity:
 *
 * - a batch keeps its argument order however many items it has;
 * - the head is read keys-only (FIFO) or without a sort etcd must do over the whole range
 *   (priority);
 * - an idle orphan sweep diffs keys instead of a transaction per claim in flight, and
 *   doesn't repeat on every empty receive;
 * - a claim whose commit response was lost is reconciled into the claimed item;
 * - enqueues, takes, receives, and acks reach the queue metrics.
 */
class QueueCostAndAmbiguityTests : StringSpec() {
  private val base = "/queue/${javaClass.simpleName}"

  private class QueueOps : EtcdMetrics {
    val ops = CopyOnWriteArrayList<String>()

    override fun recordQueue(
      op: String,
      path: String,
      duration: Duration,
    ) {
      ops += op
    }
  }

  init {
    "a batch of more than a dozen items comes out in argument order" {
      connectToEtcd(urls) { client ->
        val path = "$base/batch-order"
        client.deleteChildren(path)
        DistributedQueue(client, path).use { queue ->
          // Items from a producer whose clock runs far ahead: committed first, so first in line,
          // but their keys sort after the batch's, so etcd's sort has to reorder the range.
          val early = (0 until 20).map { "early-$it" }
          early.forEachIndexed { i, value -> client.putValue("$path/9999999999999999999-${"%05d".format(i)}", value) }
          val batch = (0 until 60).map { "v$it" }
          queue.enqueueAll(batch.map { it.asByteSequence })
          List(early.size + batch.size) { queue.dequeue().asString } shouldBe early + batch
        }
        client.deleteChildren(path)
      }
    }

    "the head is read keys-only, or without a whole-range sort" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/head-reads"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        DistributedQueue(client, "$path/fifo").use { queue ->
          queue.enqueue("a")
          client.getOptions.clear()
          queue.tryDequeue().shouldNotBeNull()
          withClue("the FIFO head read fetched values") {
            client.getOptions.first { (key, _) -> key.startsWith("$path/fifo") }.second.isKeysOnly shouldBe true
          }
        }
        DistributedPriorityQueue(client, "$path/priority", minimumWaitTime = 0.milliseconds).use { queue ->
          queue.enqueue("a", 1)
          client.getOptions.clear()
          queue.tryDequeue().shouldNotBeNull()
          withClue("the priority head read asked etcd to sort the whole range") {
            client.getOptions.first { (key, _) -> key.startsWith("$path/priority") }.second.sortOrder shouldBe
              GetOption.SortOrder.NONE
          }
        }
        etcd.deleteChildren(path)
      }
    }

    "an idle orphan sweep makes no per-claim transactions, and doesn't repeat on every empty receive" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/sweep-cost"
        etcd.deleteChildren(path)
        DistributedWorkQueue(etcd, path).use { busy ->
          repeat(20) { busy.enqueue("job-$it") }
          repeat(20) { busy.receive(10.seconds).shouldNotBeNull() } // 20 claims in flight, none orphaned
          val client = HookedClient(etcd)
          DistributedWorkQueue(client, path).use { idle ->
            client.txnCount.store(0)
            idle.tryReceive().shouldBeNull()
            withClue("the sweep checked each claim with its own transaction") { client.txnCount.load() shouldBe 0 }
            client.getOptions.clear()
            idle.tryReceive().shouldBeNull()
            withClue("an empty receive right after another swept again") {
              client.getOptions.none { (key, _) -> key.startsWith("$path/claimed") } shouldBe true
            }
          }
        }
        etcd.deleteChildren(path)
      }
    }

    "a claim whose commit response was lost is reconciled into the claimed item" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/ambiguous-claim"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("job")
          client.loseNextTxnResponse.store(true) // the claim commits, but its response is lost
          val item = withClue("the committed claim was reported as a failure") { queue.tryReceive() }
          item.shouldNotBeNull().value.asString shouldBe "job"
          item.ack() shouldBe true
        }
        etcd.deleteChildren(path)
      }
    }

    "enqueues, takes, receives, and acks reach the queue metrics" {
      connectToEtcd(urls) { client ->
        val path = "$base/metrics"
        client.deleteChildren(path)
        val queueOps = QueueOps()
        val metered = ResilienceConfig.DEFAULT.withMetrics(queueOps)
        DistributedQueue(client, "$path/fifo", metered).use { queue ->
          queue.enqueue("a")
          queue.tryDequeue().shouldNotBeNull()
        }
        DistributedWorkQueue(client, "$path/work", resilience = metered).use { queue ->
          queue.enqueue("job")
          queue.receive(10.seconds).shouldNotBeNull().ack() shouldBe true
        }
        queueOps.ops shouldContainAll listOf("enqueue", "dequeue", "receive", "ack")
        client.deleteChildren(path)
      }
    }

    "the typed putValue and getValue have Java overloads without the optional arguments" {
      val typedKv = Class.forName("io.etcd.recipes.common.TypedKVUtils")
      withClue("putValue(client, key, value, codec)") {
        typedKv.methods.any { it.name == "putValue" && it.parameterCount == 4 } shouldBe true
      }
      withClue("getValue(client, key, codec)") {
        typedKv.methods.any { it.name == "getValue" && it.parameterCount == 3 } shouldBe true
      }
    }
  }
}
