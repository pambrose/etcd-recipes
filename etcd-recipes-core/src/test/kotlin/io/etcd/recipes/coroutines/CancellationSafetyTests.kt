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

import io.etcd.jetcd.KeyValue
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.lock.EtcdLock
import io.etcd.recipes.queue.DistributedQueue
import io.etcd.recipes.queue.DistributedWorkQueue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The coroutine bridges under cancellation. No etcd needed: the recipes are mocked, and a
 * mocked blocking call cancels its own caller the instant it succeeds, which lands the
 * cancellation exactly in the window where `withContext` discards a completed result.
 *
 * - A lock, permit, or item acquired in that window is given back (unlocked, released,
 *   restored, or unclaimed), rather than leaked with the caller gone.
 * - A cancelled blocking call surfaces as cancellation however the recipe reports its
 *   interrupt: re-wrapped in a checked exception, or replaced by one with no cause.
 * - The semaphore's suspend acquire runs on a dedicated thread, never a shared IO worker
 *   that a permit-loss interrupt would hit.
 */
class CancellationSafetyTests : StringSpec() {
  // Launches [block] lazily, handing it its own Job so a mock can cancel it, then waits.
  private fun cancelledMidCall(block: suspend (Job) -> Unit) =
    runBlocking {
      val job = AtomicReference<Job?>(null)
      val launched = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { block(job.load()!!) }
      job.store(launched)
      launched.start()
      launched.join()
      launched.isCancelled shouldBe true
    }

  init {
    "withLock unlocks a lock acquired just as its caller was cancelled" {
      val lock = mockk<EtcdLock>(relaxed = true)
      cancelledMidCall { job ->
        every { lock.lock() } answers { job.cancel() }
        lock.withLock { throw AssertionError("the action must not run") }
      }
      verify(exactly = 1) { lock.unlock() }
    }

    "a bounded withLock unlocks a lock acquired just as its caller was cancelled" {
      val lock = mockk<EtcdLock>(relaxed = true)
      cancelledMidCall { job ->
        every { lock.tryLock(any<Duration>()) } answers {
          job.cancel()
          true
        }
        lock.withLock(5.seconds) { throw AssertionError("the action must not run") }
      }
      verify(exactly = 1) { lock.unlock() }
    }

    "withPermit and awaitAcquire release a permit acquired just as the caller was cancelled" {
      for (call in listOf<suspend DistributedSemaphore.() -> Unit>({ withPermit { } }, { awaitAcquire() })) {
        val semaphore = mockk<DistributedSemaphore>(relaxed = true)
        cancelledMidCall { job ->
          every { semaphore.acquire() } answers { job.cancel() }
          semaphore.call()
        }
        verify(exactly = 1) { semaphore.release() }
      }
    }

    "awaitTryAcquire releases a permit acquired just as the caller was cancelled" {
      val semaphore = mockk<DistributedSemaphore>(relaxed = true)
      cancelledMidCall { job ->
        every { semaphore.tryAcquire(any<Duration>()) } answers {
          job.cancel()
          true
        }
        semaphore.awaitTryAcquire(5.seconds)
      }
      verify(exactly = 1) { semaphore.release() }
    }

    "a queue receive restores an item taken just as its caller was cancelled" {
      val taken = mockk<KeyValue>(relaxed = true)
      val queue = mockk<DistributedQueue>(relaxed = true)
      cancelledMidCall { job ->
        every { queue.takeEntry(null) } answers {
          job.cancel()
          taken
        }
        queue.receive()
      }
      verify(exactly = 1) { queue.restoreTaken(taken) }
    }

    "a work-queue receive unclaims an item claimed just as its caller was cancelled" {
      val item = mockk<DistributedWorkQueue.WorkItem>(relaxed = true)
      val queue = mockk<DistributedWorkQueue>(relaxed = true)
      cancelledMidCall { job ->
        every { queue.receive() } answers {
          job.cancel()
          item
        }
        queue.awaitReceive()
      }
      verify(exactly = 1) { item.unclaim() }
    }

    "an interrupt re-wrapped in a checked EtcdRecipeException surfaces as cancellation" {
      runBlocking {
        shouldThrow<TimeoutCancellationException> {
          withTimeout(200.milliseconds) {
            interruptibleOn(Dispatchers.IO) {
              try {
                Thread.sleep(10_000)
              } catch (e: InterruptedException) {
                throw EtcdRecipeException("Service registration failed", EtcdRecipeRuntimeException("grant", e))
              }
            }
          }
        }
      }
    }

    "an interrupt replaced by an exception with no cause surfaces as cancellation" {
      runBlocking {
        shouldThrow<TimeoutCancellationException> {
          withTimeout(200.milliseconds) {
            interruptibleOn(Dispatchers.IO) {
              try {
                Thread.sleep(10_000)
              } catch (e: InterruptedException) {
                throw EtcdRecipeRuntimeException("Failed to set waitingPath")
              }
            }
          }
        }
      }
    }

    "a genuine failure of a live caller still propagates unchanged" {
      runBlocking {
        val thrown =
          shouldThrow<EtcdRecipeException> {
            interruptibleOn(Dispatchers.IO) { throw EtcdRecipeException("real failure") }
          }
        thrown.message shouldBe "real failure"
      }
    }

    "the semaphore's suspend acquire runs on a dedicated thread, not a shared IO worker" {
      val semaphore = mockk<DistributedSemaphore>(relaxed = true)
      val acquirer = AtomicReference<String?>(null)
      every { semaphore.acquire() } answers { acquirer.store(Thread.currentThread().name) }
      runBlocking { semaphore.awaitAcquire() }
      withClue("acquired on ${acquirer.load()}") { acquirer.load()!! shouldStartWith "etcd-suspend-" }
    }
  }
}
