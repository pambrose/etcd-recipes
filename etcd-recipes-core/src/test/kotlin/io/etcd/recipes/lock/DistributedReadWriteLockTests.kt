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
import io.etcd.recipes.common.accumulateMax
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Fair (FIFO by create revision) read-write lock semantics: readers share, writers
 * exclude, arrivals are honored in revision order (so writers cannot starve), and
 * write-to-read downgrade works while read-to-write upgrade is rejected.
 */
class DistributedReadWriteLockTests : StringSpec() {
  private val base = "/rwlock/${javaClass.simpleName}"

  init {
    "multiple readers hold concurrently" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/shared-readers"
        val concurrent = AtomicInt(0)
        val maxConcurrent = AtomicInt(0)

        blockingThreads(4) {
          connectToEtcd(urls) { c ->
            DistributedReadWriteLock(c, path).use { rw ->
              rw.readLock.withLock {
                val now = concurrent.incrementAndFetch()
                maxConcurrent.accumulateMax(now)
                Thread.sleep(500)
                concurrent.decrementAndFetch()
              }
            }
          }
        }
        maxConcurrent.load() shouldBe 4
      }
    }

    "a writer excludes readers and other writers" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/writer-excludes"
        val inCritical = AtomicInt(0)
        val maxInCritical = AtomicInt(0)
        var unguarded = 0

        blockingThreads(4) { i ->
          connectToEtcd(urls) { c ->
            DistributedReadWriteLock(c, path).use { rw ->
              val lock = if (i % 2 == 0) rw.writeLock else rw.readLock
              repeat(5) {
                lock.withLock {
                  if (i % 2 == 0) {
                    val now = inCritical.incrementAndFetch()
                    maxInCritical.accumulateMax(now)
                    unguarded += 1
                    inCritical.decrementAndFetch()
                  } else {
                    inCritical.load() shouldBe 0 // no writer while a reader holds
                  }
                }
              }
            }
          }
        }
        maxInCritical.load() shouldBeLessThanOrEqual 1
        unguarded shouldBe 10
      }
    }

    "grants honor revision order: readers share, a queued writer splits them" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/fifo"
        val grants = CopyOnWriteArrayList<String>()

        DistributedReadWriteLock(client, path).use { gate ->
          // An initial writer holds the lock while we stage arrivals behind it
          val release = BooleanMonitor(false)
          val gateHeld = BooleanMonitor(false)
          val gateThread =
            thread {
              gate.writeLock.lock()
              gateHeld.set(true)
              release.waitUntilTrue()
              gate.writeLock.unlock()
            }
          gateHeld.waitUntilTrue(15.seconds) shouldBe true

          fun arrival(
            name: String,
            write: Boolean,
            expectedEntries: Long,
          ): Thread {
            val t =
              thread(name = name) {
                connectToEtcd(urls) { c ->
                  DistributedReadWriteLock(c, path).use { rw ->
                    val lock = if (write) rw.writeLock else rw.readLock
                    lock.withLock {
                      grants += name
                      Thread.sleep(400) // overlap window so concurrent readers interleave
                    }
                  }
                }
              }
            // Stage deterministically: each arrival's entry appears under the prefix
            pollUntil(15.seconds) { client.getChildCount(path) >= expectedEntries } shouldBe true
            return t
          }

          val r1 = arrival("R1", write = false, expectedEntries = 2)
          val r2 = arrival("R2", write = false, expectedEntries = 3)
          val w1 = arrival("W1", write = true, expectedEntries = 4)
          val r3 = arrival("R3", write = false, expectedEntries = 5)

          release.set(true)
          [r1, r2, w1, r3].forEach { it.join(60_000) }
          gateThread.join(10_000)

          grants.size shouldBe 4
          // R1 and R2 (both ahead of W1) share and finish before W1; R3 waits for W1
          grants.subList(0, 2).toSet() shouldBe setOf("R1", "R2")
          grants[2] shouldBe "W1"
          grants[3] shouldBe "R3"
        }
      }
    }

    "read and write locks are reentrant per thread" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedReadWriteLock(client, "$base/reentrant").use { rw ->
          rw.readLock.lock()
          rw.readLock.lock()
          rw.readLock.holdCount shouldBe 2
          rw.readLock.unlock() shouldBe true
          rw.readLock.unlock() shouldBe true
          rw.readLock.holdCount shouldBe 0

          rw.writeLock.lock()
          rw.writeLock.lock()
          rw.writeLock.holdCount shouldBe 2
          rw.writeLock.unlock() shouldBe true
          rw.writeLock.unlock() shouldBe true
        }
      }
    }

    "read to write upgrade throws; write to read downgrade is allowed" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedReadWriteLock(client, "$base/up-down").use { rw ->
          rw.readLock.lock()
          shouldThrow<io.etcd.recipes.common.EtcdRecipeRuntimeException> { rw.writeLock.lock() }
          rw.readLock.unlock() shouldBe true

          rw.writeLock.lock()
          rw.readLock.tryLock(10.seconds) shouldBe true // downgrade: own write hold is excluded
          rw.readLock.unlock() shouldBe true
          rw.writeLock.unlock() shouldBe true
        }
      }
    }

    "tryLock times out promptly on both sides and leaves no entries behind" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/try-timeout"
        connectToEtcd(urls) { client2 ->
          DistributedReadWriteLock(client, path).use { holder ->
            DistributedReadWriteLock(client2, path).use { contender ->
              val release = BooleanMonitor(false)
              val held = BooleanMonitor(false)
              val holderThread =
                thread {
                  holder.writeLock.lock()
                  held.set(true)
                  release.waitUntilTrue()
                  holder.writeLock.unlock()
                }
              held.waitUntilTrue(15.seconds) shouldBe true

              contender.readLock.tryLock(2.seconds) shouldBe false
              contender.writeLock.tryLock(2.seconds) shouldBe false
              // Timed-out attempts must leave nothing: only the holder's entry remains
              pollUntil(10.seconds) { client.getChildCount(path) == 1L } shouldBe true

              release.set(true)
              holderThread.join(10_000)
              contender.writeLock.tryLock(10.seconds) shouldBe true
              contender.writeLock.unlock() shouldBe true
            }
          }
        }
      }
    }
  }
}
