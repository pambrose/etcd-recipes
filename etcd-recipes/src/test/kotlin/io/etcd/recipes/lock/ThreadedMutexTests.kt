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

import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Contention semantics of [DistributedMutex]: mutual exclusion across instances and
 * threads, and FIFO fairness (etcd's native lock service queues by revision).
 */
class ThreadedMutexTests : StringSpec() {
  private val base = "/mutex/${javaClass.simpleName}"

  init {
    "mutual exclusion across instances under contention" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val threadCount = 5
        val iterations = 20
        var unguarded = 0 // deliberately unsynchronized; the mutex is the only guard

        blockingThreads(threadCount) {
          connectToEtcd(urls) { c ->
            DistributedMutex(c, "$base/exclusion").use { mutex ->
              repeat(iterations) {
                mutex.withLock { unguarded += 1 }
              }
            }
          }
        }
        unguarded shouldBe threadCount * iterations
      }
    }

    "two threads sharing one instance exclude each other through etcd" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/shared-instance").use { mutex ->
          var unguarded = 0
          blockingThreads(2) {
            repeat(25) {
              mutex.withLock { unguarded += 1 }
            }
          }
          unguarded shouldBe 50
        }
      }
    }

    "waiters acquire in FIFO order" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/fifo"
        val order = CopyOnWriteArrayList<Int>()

        DistributedMutex(client, path).use { holder ->
          holder.lock()

          // Stage three waiters deterministically: each waiter's queue entry appears
          // under the lock prefix the moment its Lock RPC lands, so poll the child
          // count instead of sleeping.
          val waiters =
            (1..3).map { i ->
              val t =
                thread(name = "waiter-$i") {
                  connectToEtcd(urls) { c ->
                    DistributedMutex(c, path).use { m ->
                      m.withLock { order += i }
                    }
                  }
                }
              pollUntil(15.seconds) { client.getChildCount(path) >= 1L + i } shouldBe true
              t
            }

          holder.unlock() shouldBe true
          waiters.forEach { it.join(30_000) }
          order shouldBe listOf(1, 2, 3)
        }
      }
    }
  }
}
