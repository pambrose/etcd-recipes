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

package io.etcd.recipes.barrier

import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * `close()` must cancel an in-flight `waitOnBarrier` cleanly — returning `false` —
 * no matter where the waiter has got to:
 *
 * - before the waiting key's establish hook has run (the ready CAS, the lease grant),
 *   where a close used to make the hook decline and `waitOnBarrier` throw a cause-less
 *   `EtcdRecipeException("Failed to set waitingPath")`;
 * - after the waiting key appears but before the park, where the internal reads used to
 *   throw `EtcdRecipeRuntimeException("close() already called")`;
 * - for every concurrent waiter on the instance, not just the most recent one.
 *
 * The pre-park case is also what made `DesignFixesTests`'s fix-#2 case flaky: it closed
 * after a fixed 500ms settle, which is enough on an idle box and not under a loaded
 * parallel suite.
 */
class DistributedBarrierWithCountCloseRaceTests : StringSpec() {
  init {
    "close() cancels a waiter that has not reached the park yet" {
      val path = "/barriers/DistributedBarrierWithCountCloseRaceTests/pre-park-close"

      connectToEtcd(urls) { client ->
        repeat(ATTEMPTS) { attempt ->
          client.deleteChildren(path)

          // memberCount = 2 so this lone waiter can never be satisfied on its own:
          // the only way out of waitOnBarrier is the close() below.
          DistributedBarrierWithCount(client, path, memberCount = 2).use { barrier ->
            val finished = CountDownLatch(1)
            var result: Boolean? = null
            var thrown: Throwable? = null

            thread(isDaemon = true) {
              try {
                result = barrier.waitOnBarrier(30, TimeUnit.SECONDS)
              } catch (e: Throwable) {
                thrown = e
              } finally {
                // Always count down, so a throw reports as a throw rather than
                // masquerading as a timeout on the await below.
                finished.countDown()
              }
            }

            // Deliberately no settle sleep: closing the instant the waiting key
            // appears aims close() squarely at the pre-park window.
            pollUntil(WAIT_LIMIT) { barrier.waiterCount >= 1 } shouldBe true
            barrier.close()

            withClue("attempt $attempt: waiter never returned") {
              finished.await(WAIT_LIMIT.inWholeSeconds, TimeUnit.SECONDS) shouldBe true
            }
            withClue("attempt $attempt: close() threw out of waitOnBarrier") {
              thrown.shouldBeNull()
            }
            withClue("attempt $attempt: a cancelled wait must report not-satisfied") {
              result shouldBe false
            }
          }
        }
        client.deleteChildren(path)
      }
    }

    "close() during the ready CAS cancels the wait instead of throwing" {
      val path = "/barriers/DistributedBarrierWithCountCloseRaceTests/ready-cas-close"

      connectToEtcd(urls) { etcd ->
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)

        DistributedBarrierWithCount(client, path, memberCount = 2).use { barrier ->
          // The waiter's first txn is the ready CAS: closing there lands before the
          // waiting key's establish hook has run.
          client.beforeTxn.store { barrier.close() }
          assertCancelledPromptly(barrier)
        }

        etcd.getChildCount("$path/waiting") shouldBe 0L
        etcd.deleteChildren(path)
      }
    }

    "close() during the waiter's lease grant cancels the wait instead of throwing" {
      val path = "/barriers/DistributedBarrierWithCountCloseRaceTests/lease-grant-close"

      connectToEtcd(urls) { etcd ->
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)

        DistributedBarrierWithCount(client, path, memberCount = 2).use { barrier ->
          client.beforeLeaseGrant.store { barrier.close() }
          assertCancelledPromptly(barrier)
        }

        etcd.getChildCount("$path/waiting") shouldBe 0L
        etcd.deleteChildren(path)
      }
    }

    "close() cancels every concurrent waiter on the instance" {
      val path = "/barriers/DistributedBarrierWithCountCloseRaceTests/concurrent-close"
      val waiters = 2

      connectToEtcd(urls) { client ->
        client.deleteChildren(path)

        // memberCount exceeds the waiter count, so only close() can release them.
        DistributedBarrierWithCount(client, path, memberCount = waiters + 1).use { barrier ->
          val finished = CountDownLatch(waiters)
          val results = arrayOfNulls<Boolean>(waiters)
          val thrown = arrayOfNulls<Throwable>(waiters)

          repeat(waiters) { i ->
            thread(isDaemon = true) {
              try {
                results[i] = barrier.waitOnBarrier(30, TimeUnit.SECONDS)
              } catch (e: Throwable) {
                thrown[i] = e
              } finally {
                finished.countDown()
              }
            }
          }

          pollUntil(WAIT_LIMIT) { barrier.waiterCount >= waiters } shouldBe true
          barrier.close()

          withClue("not every waiter returned after close()") {
            finished.await(WAIT_LIMIT.inWholeSeconds, TimeUnit.SECONDS) shouldBe true
          }
          repeat(waiters) { i ->
            withClue("waiter $i") {
              thrown[i].shouldBeNull()
              results[i] shouldBe false
            }
          }
        }
        client.deleteChildren(path)
      }
    }
  }

  // The wait runs on the test thread with a generous timeout: a cancellation must come
  // back as `false` well before it, not as a throw and not by sitting out the timeout
  // (which would also return false).
  private fun assertCancelledPromptly(barrier: DistributedBarrierWithCount) {
    val (outcome, elapsed) = measureTimedValue { runCatching { barrier.waitOnBarrier(30, TimeUnit.SECONDS) } }
    withClue("close() threw out of waitOnBarrier") { outcome.exceptionOrNull().shouldBeNull() }
    withClue("a cancelled wait must report not-satisfied") { outcome.getOrNull() shouldBe false }
    withClue("the cancelled wait sat out its timeout") { elapsed shouldBeLessThan WAIT_LIMIT }
  }

  companion object {
    private const val ATTEMPTS = 15
    private val WAIT_LIMIT = 15.seconds
  }
}
