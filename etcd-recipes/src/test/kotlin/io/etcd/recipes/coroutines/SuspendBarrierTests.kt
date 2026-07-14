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

import io.etcd.recipes.barrier.DistributedBarrier
import io.etcd.recipes.barrier.DistributedBarrierWithCount
import io.etcd.recipes.barrier.DistributedDoubleBarrier
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Suspend surface for the barriers: waits complete on removal/arrival, bounded
 * waits time out, and a cancelled barrier-with-count waiter stops counting.
 */
class SuspendBarrierTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  init {
    "await returns true when the barrier is removed" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedBarrier(client, "$base/simple").use { barrier ->
          barrier.awaitSetBarrier() shouldBe true
          coroutineScope {
            val waiter = async { barrier.await() }
            delay(1_000)
            waiter.isCompleted shouldBe false

            barrier.awaitRemoveBarrier() shouldBe true
            withTimeout(10.seconds) { waiter.await() } shouldBe true
          }
        }
      }
    }

    "await with a timeout returns false while the barrier stands" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedBarrier(client, "$base/bounded").use { barrier ->
          barrier.awaitSetBarrier() shouldBe true
          barrier.await(2.seconds) shouldBe false
          barrier.awaitRemoveBarrier() shouldBe true
        }
      }
    }

    "a cancelled barrier-with-count waiter stops counting toward the barrier" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/counted"

        DistributedBarrierWithCount(client, path, memberCount = 3).use { early ->
          coroutineScope {
            val doomed = launch { early.await() }
            untilTrue(15.seconds) { early.waiterCount == 1L } shouldBe true
            doomed.cancelAndJoin()
            untilTrue(15.seconds) { early.waiterCount == 0L } shouldBe true
          }
        }

        // The cancelled waiter left nothing behind: exactly 3 fresh waiters trip it
        coroutineScope {
          val results =
            (1..3).map {
              async(Dispatchers.Default) {
                connectToEtcd(urls).use { c ->
                  DistributedBarrierWithCount(c, path, memberCount = 3).use { barrier ->
                    barrier.await(30.seconds)
                  }
                }
              }
            }
          results.awaitAll() shouldBe listOf(true, true, true)
        }
      }
    }

    "double barrier rendezvous through the suspend twins" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/double"

        coroutineScope {
          val results =
            (1..2).map {
              async(Dispatchers.Default) {
                connectToEtcd(urls).use { c ->
                  DistributedDoubleBarrier(c, path, memberCount = 2).use { barrier ->
                    val entered = barrier.awaitEnter(30.seconds)
                    val left = barrier.awaitLeave(30.seconds)
                    entered to left
                  }
                }
              }
            }
          results.awaitAll().forEach { (entered, left) ->
            entered shouldBe true
            left shouldBe true
          }
        }
      }
    }
  }
}
