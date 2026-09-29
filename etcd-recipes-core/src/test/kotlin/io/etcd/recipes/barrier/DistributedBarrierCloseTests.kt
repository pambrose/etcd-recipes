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
import io.etcd.recipes.common.deleteKey
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
 * `DistributedBarrier.close()` must cancel an in-flight `waitOnBarrier` the way
 * `DistributedBarrierWithCount.close()` does: the waiter returns `false` promptly,
 * instead of sitting out its timeout, and never throws because close() landed while it
 * was still setting up its watch.
 */
class DistributedBarrierCloseTests : StringSpec() {
  init {
    "close() unparks a waiter blocked on a set barrier" {
      val path = "/barriers/DistributedBarrierCloseTests/unpark"

      connectToEtcd(urls) { etcd ->
        etcd.deleteKey(path)
        val client = HookedClient(etcd)

        DistributedBarrier(etcd, path).use { owner ->
          owner.setBarrier() shouldBe true

          DistributedBarrier(client, path).use { barrier ->
            val watching = CountDownLatch(1)
            val finished = CountDownLatch(1)
            var result: Boolean? = null
            var thrown: Throwable? = null
            client.afterWatch.store { watching.countDown() }

            thread(isDaemon = true) {
              try {
                result = barrier.waitOnBarrier(30, TimeUnit.SECONDS)
              } catch (e: Throwable) {
                thrown = e
              } finally {
                finished.countDown()
              }
            }

            // The waiter's watch exists, so it is at (or about to reach) the park.
            watching.await(WAIT_LIMIT.inWholeSeconds, TimeUnit.SECONDS) shouldBe true
            barrier.close()

            withClue("close() did not unpark the waiter") {
              finished.await(WAIT_LIMIT.inWholeSeconds, TimeUnit.SECONDS) shouldBe true
            }
            withClue("close() threw out of waitOnBarrier") { thrown.shouldBeNull() }
            withClue("a cancelled wait must report not-released") { result shouldBe false }
          }

          owner.removeBarrier() shouldBe true
        }
      }
    }

    "close() during watch setup cancels the wait instead of throwing" {
      val path = "/barriers/DistributedBarrierCloseTests/watch-setup"

      connectToEtcd(urls) { etcd ->
        etcd.deleteKey(path)
        val client = HookedClient(etcd)

        DistributedBarrier(etcd, path).use { owner ->
          owner.setBarrier() shouldBe true

          // waitOnMissingBarriers = false puts a presence re-check between the watch
          // going live and the park; close() lands just before it.
          DistributedBarrier(client, path, waitOnMissingBarriers = false).use { barrier ->
            client.afterWatch.store { barrier.close() }

            val (outcome, elapsed) =
              measureTimedValue { runCatching { barrier.waitOnBarrier(30, TimeUnit.SECONDS) } }
            withClue("close() threw out of waitOnBarrier") { outcome.exceptionOrNull().shouldBeNull() }
            withClue("a cancelled wait must report not-released") { outcome.getOrNull() shouldBe false }
            withClue("the cancelled wait sat out its timeout") { elapsed shouldBeLessThan WAIT_LIMIT }
          }

          owner.removeBarrier() shouldBe true
        }
      }
    }
  }

  companion object {
    private val WAIT_LIMIT = 15.seconds
  }
}
