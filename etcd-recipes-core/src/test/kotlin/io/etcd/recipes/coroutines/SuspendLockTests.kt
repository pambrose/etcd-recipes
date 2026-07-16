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

import io.etcd.recipes.common.accumulateMax
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.urls
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedReadWriteLock
import io.etcd.recipes.lock.DistributedSemaphore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Executors
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.seconds

/**
 * Suspend surface for the lock suite. EtcdLock holds are thread-owned, so the only
 * suspend form is the scoped withLock (acquire and release confined to one dedicated
 * thread per call); the semaphore's instance-held permits get the full split API.
 */
class SuspendLockTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  init {
    "split lock and unlock on different threads throws: why the suspend surface is scoped-only" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/split").use { mutex ->
          val d1 = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
          val d2 = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
          try {
            runInterruptible(d1) { mutex.lock() }
            shouldThrow<IllegalMonitorStateException> {
              runInterruptible(d2) { mutex.unlock() }
            }
            runInterruptible(d1) { mutex.unlock() } shouldBe true
          } finally {
            d1.close()
            d2.close()
          }
        }
      }
    }

    "withLock serializes concurrent coroutines" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val inCritical = AtomicInt(0)
        val maxInCritical = AtomicInt(0)
        var unguarded = 0

        DistributedMutex(client, "$base/serialize").use { mutex ->
          coroutineScope {
            repeat(4) {
              launch(Dispatchers.Default) {
                repeat(3) {
                  mutex.withLock {
                    val now = inCritical.incrementAndFetch()
                    maxInCritical.accumulateMax(now)
                    unguarded += 1
                    delay(50)
                    inCritical.decrementAndFetch()
                  }
                }
              }
            }
          }
        }
        maxInCritical.load() shouldBeLessThanOrEqual 1
        unguarded shouldBe 12
      }
    }

    "withLock releases on exception" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/exception").use { mutex ->
          shouldThrow<IllegalArgumentException> {
            mutex.withLock { throw IllegalArgumentException("boom") }
          }
          mutex.withLock(5.seconds) { "reacquired" } shouldBe "reacquired"
        }
      }
    }

    "withLock with timeout returns null while held and the value when free" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/timeout"
        DistributedMutex(client, path).use { holderSide ->
          DistributedMutex(client, path).use { contenderSide ->
            coroutineScope {
              val held = CompletableDeferred<Unit>()
              val release = CompletableDeferred<Unit>()
              val holder =
                launch {
                  holderSide.withLock {
                    held.complete(Unit)
                    release.await()
                  }
                }
              held.await()

              contenderSide.withLock(2.seconds) { "never" } shouldBe null

              release.complete(Unit)
              holder.join()
              contenderSide.withLock(10.seconds) { "got it" } shouldBe "got it"
            }
          }
        }
      }
    }

    "cancelling a coroutine parked in withLock leaks nothing" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/cancel"
        DistributedMutex(client, path).use { holderSide ->
          DistributedMutex(client, path).use { waiterSide ->
            coroutineScope {
              val held = CompletableDeferred<Unit>()
              val release = CompletableDeferred<Unit>()
              val holder =
                launch {
                  holderSide.withLock {
                    held.complete(Unit)
                    release.await()
                  }
                }
              held.await()

              val waiter = launch { waiterSide.withLock { "never" } }
              untilTrue(15.seconds) { client.getChildCount(path) == 2L } shouldBe true

              waiter.cancelAndJoin()
              untilTrue(15.seconds) { client.getChildCount(path) == 1L } shouldBe true

              release.complete(Unit)
              holder.join()
              waiterSide.withLock(10.seconds) { "after" } shouldBe "after"
            }
          }
        }
      }
    }

    "withLock is not reentrant: a nested attempt on the same lock times out" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/nested").use { mutex ->
          mutex.withLock {
            mutex.withLock(2.seconds) { "inner" } shouldBe null
            "outer"
          } shouldBe "outer"
        }
      }
    }

    "read lock shares and write lock excludes through withLock" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/rw"
        DistributedReadWriteLock(client, path).use { rw ->
          val concurrent = AtomicInt(0)
          val maxConcurrent = AtomicInt(0)
          coroutineScope {
            repeat(2) {
              launch(Dispatchers.Default) {
                rw.readLock.withLock {
                  val now = concurrent.incrementAndFetch()
                  maxConcurrent.accumulateMax(now)
                  delay(500)
                  concurrent.decrementAndFetch()
                }
              }
            }
          }
          maxConcurrent.load() shouldBe 2

          coroutineScope {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer =
              launch {
                rw.writeLock.withLock {
                  held.complete(Unit)
                  release.await()
                }
              }
            held.await()
            rw.readLock.withLock(2.seconds) { "never" } shouldBe null
            release.complete(Unit)
            writer.join()
          }
        }
      }
    }

    "semaphore split acquire and release survive dispatcher hops" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedSemaphore(client, "$base/sem-split", 1).use { sem ->
          sem.awaitAcquire()
          delay(100) // resumption may land on a different IO thread — instance-held, so fine
          sem.awaitAvailablePermits() shouldBe 0
          sem.awaitRelease() shouldBe true
          sem.awaitAvailablePermits() shouldBe 1
        }
      }
    }

    "withPermit caps concurrency and releases on cancellation" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/permits"
        val inFlight = AtomicInt(0)
        val maxInFlight = AtomicInt(0)

        DistributedSemaphore(client, path, 2).use { sem ->
          coroutineScope {
            repeat(5) {
              launch(Dispatchers.Default) {
                sem.withPermit {
                  val now = inFlight.incrementAndFetch()
                  maxInFlight.accumulateMax(now)
                  delay(200)
                  inFlight.decrementAndFetch()
                }
              }
            }
          }
          maxInFlight.load() shouldBeLessThanOrEqual 2
        }

        DistributedSemaphore(client, "$base/permit-cancel", 1).use { sem ->
          coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val holder =
              launch {
                sem.withPermit {
                  entered.complete(Unit)
                  awaitCancellation()
                }
              }
            entered.await()
            holder.cancelAndJoin()

            sem.awaitTryAcquire(10.seconds) shouldBe true
            sem.awaitRelease() shouldBe true
          }
        }
      }
    }
  }
}
