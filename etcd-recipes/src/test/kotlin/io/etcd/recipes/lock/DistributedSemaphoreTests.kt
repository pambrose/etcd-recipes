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

package io.etcd.recipes.lock

import com.pambrose.common.concurrent.BooleanMonitor
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Counting-semaphore semantics: capacity is never exceeded, grants are FIFO by
 * arrival revision, releases are LIFO among this instance's holds, timeouts leak
 * no queue entries, and the canonical permit count is validated on first use.
 */
class DistributedSemaphoreTests : StringSpec() {
  private val base = "/semaphore/${javaClass.simpleName}"

  init {
    "capacity is never exceeded under contention" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/capacity"
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val completions = AtomicInteger(0)

        blockingThreads(8) {
          connectToEtcd(urls) { c ->
            DistributedSemaphore(c, path, 3).use { sem ->
              sem.withPermit {
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(300)
                inFlight.decrementAndGet()
                completions.incrementAndGet()
              }
            }
          }
        }
        maxInFlight.get() shouldBeLessThanOrEqual 3
        completions.get() shouldBe 8
      }
    }

    "grants are FIFO by arrival order" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/fifo"
        val holdersPath = "$path/holders"
        val grants = CopyOnWriteArrayList<String>()

        DistributedSemaphore(client, path, 1).use { gate ->
          val release = BooleanMonitor(false)
          val gateHeld = BooleanMonitor(false)
          val gateThread =
            thread {
              gate.acquire()
              gateHeld.set(true)
              release.waitUntilTrue()
              gate.release()
            }
          gateHeld.waitUntilTrue(15.seconds) shouldBe true

          // Stage each waiter deterministically: its queue entry must exist
          // before the next arrival is launched
          fun arrival(
            name: String,
            expectedCount: Long,
          ): Thread {
            val t =
              thread {
                connectToEtcd(urls) { c ->
                  DistributedSemaphore(c, path, 1).use { sem ->
                    sem.acquire()
                    grants += name
                    Thread.sleep(200)
                    sem.release()
                  }
                }
              }
            pollUntil(15.seconds) { client.getChildCount(holdersPath) == expectedCount } shouldBe true
            return t
          }

          val w1 = arrival("w1", 2)
          val w2 = arrival("w2", 3)

          release.set(true)
          gateThread.join(10_000)
          w1.join(15_000)
          w2.join(15_000)
          grants.toList() shouldBe listOf("w1", "w2")
        }
      }
    }

    "release frees the most recently acquired permit first" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/lifo"
        val holdersPath = "$path/holders"

        DistributedSemaphore(client, path, 2).use { sem ->
          sem.acquire()
          val firstRev =
            client.getResponse(holdersPath, getOption { isPrefix(true) }).kvs.single().createRevision
          sem.acquire()
          client.getChildCount(holdersPath) shouldBe 2L

          sem.release() shouldBe true
          val remaining = client.getResponse(holdersPath, getOption { isPrefix(true) }).kvs.single()
          remaining.createRevision shouldBe firstRev
          sem.release() shouldBe true
        }
      }
    }

    "tryAcquire times out promptly and leaves no waiter entry" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/timeout"
        val holdersPath = "$path/holders"

        DistributedSemaphore(client, path, 1).use { holder ->
          holder.acquire()
          connectToEtcd(urls) { client2 ->
            DistributedSemaphore(client2, path, 1).use { contender ->
              contender.tryAcquire(2.seconds) shouldBe false
              client.getChildCount(holdersPath) shouldBe 1L

              holder.release() shouldBe true
              contender.tryAcquire(10.seconds) shouldBe true
              contender.release() shouldBe true
            }
          }
        }
      }
    }

    "tryAcquire succeeds when a permit frees within the timeout" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/timely"

        DistributedSemaphore(client, path, 1).use { holder ->
          holder.acquire()
          val releaser =
            thread {
              Thread.sleep(1_000)
              holder.release()
            }
          connectToEtcd(urls) { client2 ->
            DistributedSemaphore(client2, path, 1).use { contender ->
              contender.tryAcquire(15.seconds) shouldBe true
              contender.release() shouldBe true
            }
          }
          releaser.join(10_000)
        }
      }
    }

    "release without a hold throws IllegalStateException" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedSemaphore(client, "$base/none", 1).use { sem ->
          shouldThrow<IllegalStateException> { sem.release() }
        }
      }
    }

    "withPermit releases the permit when the block throws" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/with-permit"
        val holdersPath = "$path/holders"

        DistributedSemaphore(client, path, 1).use { sem ->
          shouldThrow<IllegalArgumentException> {
            sem.withPermit { throw IllegalArgumentException("boom") }
          }
          client.getChildCount(holdersPath) shouldBe 0L
          sem.availablePermits() shouldBe 1
        }
      }
    }

    "mismatched permit counts fail on first use naming both values" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/mismatch"

        DistributedSemaphore(client, path, 2).use { first ->
          first.acquire()
          connectToEtcd(urls) { client2 ->
            DistributedSemaphore(client2, path, 3).use { second ->
              val e = shouldThrow<SemaphorePermitMismatchException> { second.acquire() }
              e.requestedPermits shouldBe 3
              e.canonicalPermits shouldBe 2
            }
          }
          first.release() shouldBe true
        }
      }
    }

    "availablePermits tracks holds" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/available"

        DistributedSemaphore(client, path, 2).use { sem ->
          sem.availablePermits() shouldBe 2
          sem.acquire()
          sem.availablePermits() shouldBe 1
          sem.acquire()
          sem.availablePermits() shouldBe 0
          sem.release() shouldBe true
          sem.availablePermits() shouldBe 1
          sem.release() shouldBe true
          sem.availablePermits() shouldBe 2
        }
      }
    }

    "close releases all held permits and later release returns false" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/close"
        val holdersPath = "$path/holders"

        val holder = DistributedSemaphore(client, path, 1)
        holder.acquire()
        connectToEtcd(urls) { client2 ->
          DistributedSemaphore(client2, path, 1).use { successor ->
            val acquired = BooleanMonitor(false)
            val waiter =
              thread {
                successor.acquire()
                acquired.set(true)
                successor.release()
              }
            pollUntil(15.seconds) { client.getChildCount(holdersPath) == 2L } shouldBe true

            holder.close()

            acquired.waitUntilTrue(20.seconds) shouldBe true
            waiter.join(10_000)
          }
        }
        holder.release() shouldBe false
      }
    }
  }
}
