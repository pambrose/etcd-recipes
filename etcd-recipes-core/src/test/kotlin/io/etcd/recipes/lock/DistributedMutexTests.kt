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
import io.etcd.recipes.common.urls
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Single-thread semantics of [DistributedMutex]: reentrancy, ownership rules,
 * withLock, and lifecycle (product idea #1, PR 1).
 */
class DistributedMutexTests : StringSpec() {
  private val base = "/mutex/${javaClass.simpleName}"

  init {
    "lock is reentrant and holdCount tracks per-thread holds" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/reentrant").use { mutex ->
          mutex.holdCount shouldBe 0
          mutex.lock()
          mutex.isHeldByCurrentThread shouldBe true
          mutex.holdCount shouldBe 1
          mutex.lock()
          mutex.holdCount shouldBe 2
          mutex.unlock() shouldBe true
          mutex.holdCount shouldBe 1
          mutex.isHeldByCurrentThread shouldBe true
          mutex.unlock() shouldBe true
          mutex.holdCount shouldBe 0
          mutex.isHeldByCurrentThread shouldBe false
        }
      }
    }

    "unlock by a thread that never held the lock throws IllegalMonitorStateException" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/non-owner").use { mutex ->
          mutex.lock()
          val thrown = AtomicReference<Throwable?>(null)
          thread {
            try {
              mutex.unlock()
            } catch (e: Throwable) {
              thrown.store(e)
            }
          }.join(10_000)
          (thrown.load() is IllegalMonitorStateException) shouldBe true
          mutex.unlock() shouldBe true
        }
      }
    }

    "withLock releases on exception" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        DistributedMutex(client, "$base/with-lock").use { mutex ->
          shouldThrow<IllegalStateException> {
            mutex.withLock { throw IllegalStateException("inside") }
          }
          mutex.isHeldByCurrentThread shouldBe false
          mutex.isLocked shouldBe false
        }
      }
    }

    "isLocked reflects a holder from another instance" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        connectToEtcd(urls) { client2 ->
          DistributedMutex(client, "$base/is-locked").use { holder ->
            DistributedMutex(client2, "$base/is-locked").use { observer ->
              observer.isLocked shouldBe false
              holder.lock()
              observer.isLocked shouldBe true
              observer.isHeldByCurrentThread shouldBe false
              holder.unlock() shouldBe true
              observer.isLocked shouldBe false
            }
          }
        }
      }
    }

    "close releases a held lock and later unlock returns false" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        connectToEtcd(urls) { client2 ->
          val mutex = DistributedMutex(client, "$base/close")
          mutex.lock()
          mutex.close()

          DistributedMutex(client2, "$base/close").use { next ->
            next.tryLock(10.seconds) shouldBe true
            next.unlock() shouldBe true
          }
          mutex.unlock() shouldBe false // released by close(), not by this thread
        }
      }
    }
  }
}
