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

import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.urls
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.DistributedQueue
import io.etcd.recipes.queue.DistributedWorkQueue
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real-etcd halves of the cancellation-safety fixes:
 *
 * - an item handed back after a late cancellation goes back under its original key: a
 *   priority queue keeps its place, a FIFO queue (ordered by commit revision) gets it at
 *   the tail, and a work queue gets it back without a spent delivery attempt;
 * - with `interruptOnLockLoss` / `interruptOnPermitLoss`, losing the hold cancels the
 *   suspend holder's action, which fails with [HoldLostException]. For a semaphore, only
 *   the holder whose permit was lost; without the option, the action keeps running.
 */
class SuspendHoldLossTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  private fun leasedUnder(
    client: Client,
    prefix: String,
  ): List<KeyValue> = client.getResponse(prefix, getOption { isPrefix(true) }).kvs.filter { it.lease > 0L }

  init {
    "a restored item keeps its place in a priority queue" {
      connectToEtcd(urls) { client ->
        val path = "$base/restore-priority"
        client.deleteChildren(path)
        DistributedPriorityQueue(client, path, minimumWaitTime = 0.milliseconds).use { queue ->
          queue.enqueue("a", 1)
          queue.enqueue("b", 2)
          val taken = queue.takeEntry(null).shouldNotBeNull()
          taken.value.asString shouldBe "a"
          queue.restoreTaken(taken)
          queue.dequeue().asString shouldBe "a"
          queue.dequeue().asString shouldBe "b"
        }
        client.deleteChildren(path)
      }
    }

    "a restored item rejoins a FIFO queue, which orders by commit revision" {
      connectToEtcd(urls) { client ->
        val path = "$base/restore-fifo"
        client.deleteChildren(path)
        DistributedQueue(client, path).use { queue ->
          queue.enqueue("a")
          queue.enqueue("b")
          val taken = queue.takeEntry(null).shouldNotBeNull()
          taken.value.asString shouldBe "a"
          queue.restoreTaken(taken)
          queue.dequeue().asString shouldBe "b"
          queue.dequeue().asString shouldBe "a"
        }
        client.deleteChildren(path)
      }
    }

    "a work item unclaimed after a late cancellation is redelivered without spending an attempt" {
      connectToEtcd(urls) { client ->
        val path = "$base/unclaim"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("job")
          val first = queue.receive(10.seconds).shouldNotBeNull()
          first.attempt shouldBe 1
          first.unclaim() shouldBe true
          val again = queue.receive(10.seconds).shouldNotBeNull()
          again.id shouldBe first.id
          again.attempt shouldBe 1
          again.ack() shouldBe true
        }
        client.deleteChildren(path)
      }
    }

    "with interruptOnLockLoss, losing the lock cancels withLock's action with HoldLostException" {
      connectToEtcd(urls) { client ->
        val path = "$base/mutex-loss"
        client.deleteChildren(path)
        DistributedMutex(client, path, interruptOnLockLoss = true).use { mutex ->
          runBlocking(Dispatchers.Default) {
            val started = CompletableDeferred<Unit>()
            val holder =
              async {
                runCatching {
                  mutex.withLock {
                    started.complete(Unit)
                    awaitCancellation()
                  }
                }
              }
            withTimeout(10.seconds) { started.await() }
            client.leaseClient.revoke(leasedUnder(client, path).single().lease).get()
            val outcome = withTimeout(20.seconds) { holder.await() }
            withClue("outcome: $outcome") { (outcome.exceptionOrNull() is HoldLostException) shouldBe true }
          }
        }
        client.deleteChildren(path)
      }
    }

    "without interruptOnLockLoss, withLock's action keeps running after the lock is lost" {
      connectToEtcd(urls) { client ->
        val path = "$base/mutex-no-cancel"
        client.deleteChildren(path)
        DistributedMutex(client, path).use { mutex ->
          runBlocking(Dispatchers.Default) {
            val started = CompletableDeferred<Unit>()
            val lost = CompletableDeferred<Unit>()
            mutex.addLockLostListener { lost.complete(Unit) }
            val holder =
              async {
                mutex.withLock {
                  started.complete(Unit)
                  lost.await()
                  delay(500.milliseconds) // still running well after the loss
                  "finished"
                }
              }
            withTimeout(10.seconds) { started.await() }
            client.leaseClient.revoke(leasedUnder(client, path).single().lease).get()
            withTimeout(20.seconds) { holder.await() } shouldBe "finished"
          }
        }
        client.deleteChildren(path)
      }
    }

    "with interruptOnPermitLoss, only the holder whose permit was lost is cancelled" {
      connectToEtcd(urls) { client ->
        val path = "$base/permit-loss"
        client.deleteChildren(path)
        DistributedSemaphore(client, path, 2, interruptOnPermitLoss = true).use { semaphore ->
          runBlocking(Dispatchers.Default) {
            val bothHeld = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var holding = 0
            val holders =
              List(2) {
                async {
                  runCatching {
                    semaphore.withPermit {
                      synchronized(this@SuspendHoldLossTests) { if (++holding == 2) bothHeld.complete(Unit) }
                      release.await()
                      "finished"
                    }
                  }
                }
              }
            withTimeout(10.seconds) { bothHeld.await() }
            client.leaseClient.revoke(leasedUnder(client, "$path/holders").first().lease).get()
            val lostOne = withTimeout(20.seconds) {
              kotlinx.coroutines.selects.select {
                holders.forEach { h ->
                  h.onAwait { it }
                }
              }
            }
            withClue("first outcome: $lostOne") { (lostOne.exceptionOrNull() is HoldLostException) shouldBe true }
            delay(1.seconds)
            withClue("the holder that kept its permit was cancelled too") {
              holders.count { it.isCompleted } shouldBe 1
            }
            release.complete(Unit)
            holders.map { it.await() }.count { it.getOrNull() == "finished" } shouldBe 1
          }
        }
        client.deleteChildren(path)
      }
    }
  }
}
