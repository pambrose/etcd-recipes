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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Bounded acquisition: tryLock must time out promptly and — critically — leave no
 * waiter key or lease behind (the timed-out attempt's lease revoke is the sole
 * authoritative abort of the server-side wait).
 */
class MutexTryLockTests : StringSpec() {
  private val base = "/mutex/${javaClass.simpleName}"

  init {
    "tryLock times out promptly while held elsewhere and leaks nothing" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/timeout"
        connectToEtcd(urls) { client2 ->
          DistributedMutex(client, path).use { holder ->
            DistributedMutex(client2, path).use { contender ->
              holder.lock()
              client.getChildCount(path) shouldBe 1L

              val start = TimeSource.Monotonic.markNow()
              contender.tryLock(2.seconds) shouldBe false
              (start.elapsedNow() >= 1.5.seconds) shouldBe true
              (start.elapsedNow() < 20.seconds) shouldBe true

              // No leaked waiter entry: the timed-out attempt's key must vanish
              pollUntil(10.seconds) { client.getChildCount(path) == 1L } shouldBe true

              // And the holder's release must promote nobody stale: a fresh lock()
              // succeeds immediately afterwards
              holder.unlock() shouldBe true
              contender.tryLock(5.seconds) shouldBe true
              contender.unlock() shouldBe true
            }
          }
        }
      }
    }

    "tryLock succeeds when the lock frees within the timeout" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/frees-in-time"
        connectToEtcd(urls) { client2 ->
          DistributedMutex(client, path).use { holder ->
            DistributedMutex(client2, path).use { contender ->
              // Holds are thread-owned: lock and unlock must run on the same thread
              val held = com.pambrose.common.concurrent.BooleanMonitor(false)
              val holderThread =
                thread {
                  holder.lock()
                  held.set(true)
                  Thread.sleep(1_000)
                  holder.unlock()
                }
              held.waitUntilTrue(10.seconds) shouldBe true

              contender.tryLock(20.seconds) shouldBe true
              contender.isHeldByCurrentThread shouldBe true
              contender.unlock() shouldBe true
              holderThread.join(10_000)
            }
          }
        }
      }
    }

    "reentrant tryLock returns immediately" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/reentrant-try").use { mutex ->
          mutex.lock()
          val start = TimeSource.Monotonic.markNow()
          mutex.tryLock(30.seconds) shouldBe true
          (start.elapsedNow() < 5.seconds) shouldBe true
          mutex.holdCount shouldBe 2
          mutex.unlock() shouldBe true
          mutex.unlock() shouldBe true
        }
      }
    }
  }
}
