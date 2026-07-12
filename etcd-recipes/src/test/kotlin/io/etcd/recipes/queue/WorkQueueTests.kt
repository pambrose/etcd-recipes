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
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * At-least-once work-queue semantics (product idea #4, phase B): claims, acks,
 * crash redelivery, and dead-lettering. Consumer crashes are simulated for real by
 * revoking the consumer's lease out-of-band (etcd exposes the claim key's lease id)
 * — exactly what a process death or partition longer than the visibility timeout
 * produces.
 */
class WorkQueueTests : StringSpec() {
  private val base = "/workqueue/${javaClass.simpleName}"

  /** Kills a consumer's claims the way a crash would: revoke the claim's lease. */
  private fun revokeClaimLease(
    client: Client,
    queuePath: String,
  ) {
    val claimKeys = client.getChildrenKeys("$queuePath/claims")
    claimKeys.shouldNotBeNull()
    (claimKeys.isNotEmpty()) shouldBe true
    val leaseId = client.getResponse(claimKeys.first()).kvs.first().lease
    (leaseId > 0L) shouldBe true
    client.leaseClient.revoke(leaseId).get()
  }

  init {
    "enqueue then receive delivers the payload as delivery attempt 1" {
      connectToEtcd(urls) { client ->
        val path = "$base/basic"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("job-1")
          val item = queue.receive(10.seconds)
          item.shouldNotBeNull()
          item.value.asString shouldBe "job-1"
          item.attempt shouldBe 1
          item.ack() shouldBe true
        }
      }
    }

    "ack removes every trace of the item" {
      connectToEtcd(urls) { client ->
        val path = "$base/ack"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("done-job")
          queue.receive(10.seconds)!!.ack() shouldBe true
          queue.tryReceive().shouldBeNull()
          client.getChildrenKeys(path).shouldBeEmpty()
        }
      }
    }

    "an unacked item from a crashed consumer is redelivered with attempt 2" {
      connectToEtcd(urls) { client ->
        val path = "$base/redeliver"
        client.deleteChildren(path)
        connectToEtcd(urls) { client2 ->
          DistributedWorkQueue(client, path).use { crashed ->
            DistributedWorkQueue(client2, path).use { survivor ->
              crashed.enqueue("fragile-job")
              val first = crashed.receive(10.seconds)
              first.shouldNotBeNull()
              first.attempt shouldBe 1
              // no ack: the consumer "crashes"
              revokeClaimLease(client, path)

              // the survivor's receive reclaims the orphan and re-delivers it
              val second = survivor.receive(30.seconds)
              second.shouldNotBeNull()
              second.value.asString shouldBe "fragile-job"
              second.attempt shouldBe 2
              second.ack() shouldBe true
            }
          }
        }
      }
    }

    "requeue returns the item to its original position with attempts preserved" {
      connectToEtcd(urls) { client ->
        val path = "$base/requeue"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("first")
          queue.enqueue("second")

          val item = queue.receive(10.seconds)
          item!!.value.asString shouldBe "first"
          item.requeue() shouldBe true

          // original FIFO position: "first" comes out again before "second"
          val again = queue.receive(10.seconds)
          again!!.value.asString shouldBe "first"
          again.attempt shouldBe 2
          again.ack() shouldBe true
          queue.receive(10.seconds)!!.also { it.value.asString shouldBe "second" }.ack()
        }
      }
    }

    "the item dead-letters after maxDeliveries and stops being delivered" {
      connectToEtcd(urls) { client ->
        val path = "$base/dlq"
        client.deleteChildren(path)
        val config = WorkQueueConfig(maxDeliveries = 2)
        DistributedWorkQueue(client, path, config).use { queue ->
          queue.enqueue("poison-pill")

          repeat(2) {
            queue.receive(10.seconds).shouldNotBeNull()
            revokeClaimLease(client, path)
            // wait until the claim marker is really gone before reclaiming
            pollUntil(10.seconds) { client.getChildrenKeys("$path/claims").isEmpty() } shouldBe true
          }

          // reclaim now routes the poison pill to the DLQ instead of redelivering
          queue.receive(5.seconds).shouldBeNull()
          val dead = queue.deadLetters()
          dead shouldHaveSize 1
          dead.first().value.asString shouldBe "poison-pill"
          dead.first().attempts shouldBe 2
        }
      }
    }

    "requeueDeadLetter returns the item to the queue with a fresh attempt count" {
      connectToEtcd(urls) { client ->
        val path = "$base/dlq-requeue"
        client.deleteChildren(path)
        val config = WorkQueueConfig(maxDeliveries = 1)
        DistributedWorkQueue(client, path, config).use { queue ->
          queue.enqueue("second-chance")
          queue.receive(10.seconds).shouldNotBeNull()
          revokeClaimLease(client, path)
          pollUntil(10.seconds) { client.getChildrenKeys("$path/claims").isEmpty() } shouldBe true

          queue.receive(5.seconds).shouldBeNull() // dead-lettered
          val dead = queue.deadLetters().first()

          queue.requeueDeadLetter(dead.id) shouldBe true
          val revived = queue.receive(10.seconds)
          revived!!.value.asString shouldBe "second-chance"
          revived.attempt shouldBe 1 // fresh start
          revived.ack() shouldBe true
          queue.deadLetters().shouldBeEmpty()
        }
      }
    }

    "purgeDeadLetter discards the item permanently" {
      connectToEtcd(urls) { client ->
        val path = "$base/dlq-purge"
        client.deleteChildren(path)
        val config = WorkQueueConfig(maxDeliveries = 1)
        DistributedWorkQueue(client, path, config).use { queue ->
          queue.enqueue("hopeless")
          queue.receive(10.seconds).shouldNotBeNull()
          revokeClaimLease(client, path)
          pollUntil(10.seconds) { client.getChildrenKeys("$path/claims").isEmpty() } shouldBe true
          queue.receive(5.seconds).shouldBeNull()

          val dead = queue.deadLetters().first()
          queue.purgeDeadLetter(dead.id) shouldBe true
          queue.deadLetters().shouldBeEmpty()
          queue.tryReceive().shouldBeNull()
        }
      }
    }

    "ack returns false once the claim was lost to redelivery" {
      connectToEtcd(urls) { client ->
        val path = "$base/lost-claim"
        client.deleteChildren(path)
        connectToEtcd(urls) { client2 ->
          DistributedWorkQueue(client, path).use { slow ->
            DistributedWorkQueue(client2, path).use { fast ->
              slow.enqueue("contended")
              val slowItem = slow.receive(10.seconds)
              slowItem.shouldNotBeNull()
              revokeClaimLease(client, path)

              val fastItem = fast.receive(30.seconds)
              fastItem.shouldNotBeNull()
              fastItem.ack() shouldBe true

              // the original consumer's ack must fail: its work may have been redone
              slowItem.ack() shouldBe false
            }
          }
        }
      }
    }

    "receive with a timeout returns null on an empty queue within bounds" {
      connectToEtcd(urls) { client ->
        val path = "$base/empty"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          val start = TimeSource.Monotonic.markNow()
          queue.receive(2.seconds).shouldBeNull()
          (start.elapsedNow() >= 1.5.seconds) shouldBe true
          (start.elapsedNow() < 30.seconds) shouldBe true
        }
      }
    }

    "concurrent consumers each receive distinct items exactly once" {
      connectToEtcd(urls) { client ->
        val path = "$base/concurrent"
        client.deleteChildren(path)
        val count = 20
        val seen = ConcurrentHashMap.newKeySet<String>()

        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueueAll((1..count).map { "item-$it".toByteArray().let(io.etcd.jetcd.ByteSequence::from) })

          val workers =
            (1..3).map {
              thread {
                connectToEtcd(urls) { c ->
                  DistributedWorkQueue(c, path).use { consumer ->
                    while (true) {
                      val item = consumer.receive(3.seconds) ?: break
                      seen.add(item.value.asString) shouldBe true // never delivered twice
                      item.ack() shouldBe true
                    }
                  }
                }
              }
            }
          workers.forEach { it.join(60_000) }
          seen shouldHaveSize count
        }
      }
    }
  }
}
