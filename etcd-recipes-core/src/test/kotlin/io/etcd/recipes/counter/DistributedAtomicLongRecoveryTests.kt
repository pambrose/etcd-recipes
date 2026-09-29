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

package io.etcd.recipes.counter

import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * `DistributedAtomicLong` recovers from a missing counter key instead of breaking:
 *
 * - a failed first-use initialization is retried by the next call (it used to leave the
 *   instance permanently returning -1 from `get()` and throwing from every update);
 * - a counter deleted by another process reads as its default and restarts from it;
 * - `close()` ends a compare-and-set loop that keeps losing, and its backoff is capped.
 */
class DistributedAtomicLongRecoveryTests : StringSpec() {
  private val base = "/counters/${javaClass.simpleName}"

  init {
    "a failed first-use initialization is retried by the next call" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/init-failure"
        DistributedAtomicLong.delete(etcd, path)
        val client = HookedClient(etcd)
        DistributedAtomicLong(client, path).use { counter ->
          client.beforeTxn.store { throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("leader changed")) }
          shouldThrowAny { counter.increment() }
          counter.increment() shouldBe 1L
          counter.get() shouldBe 1L
        }
        DistributedAtomicLong.delete(etcd, path)
      }
    }

    "a counter deleted by another process reads as its default and restarts from it" {
      connectToEtcd(urls) { client ->
        val path = "$base/deleted"
        DistributedAtomicLong.delete(client, path)
        DistributedAtomicLong(client, path, default = 10L).use { counter ->
          counter.add(5L) shouldBe 15L
          DistributedAtomicLong.delete(client, path)
          counter.get() shouldBe 10L
          counter.increment() shouldBe 11L
          counter.get() shouldBe 11L
        }
        DistributedAtomicLong.delete(client, path)
      }
    }

    "close() ends a compare-and-set loop that keeps losing" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/close-in-loop"
        DistributedAtomicLong.delete(etcd, path)
        val client = HookedClient(etcd)
        val rival = DistributedAtomicLong(etcd, path)
        val contend = AtomicBoolean(true)
        val lostAttempts = AtomicInt(0)

        // Before each of the counter's transactions, a rival bumps the key, so every
        // compare-and-set loses for as long as contend is set.
        fun rearm() {
          client.beforeTxn.store {
            if (contend.load()) {
              rival.increment()
              lostAttempts.incrementAndFetch()
              rearm()
            }
          }
        }

        val counter = DistributedAtomicLong(client, path).start()
        rearm()
        var thrown: Throwable? = null
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
          try {
            counter.increment()
          } catch (e: Throwable) {
            thrown = e
          } finally {
            done.countDown()
          }
        }
        try {
          pollUntil(10.seconds) { lostAttempts.load() >= 3 } shouldBe true
          counter.close()
          withClue("the compare-and-set loop ignored close()") { done.await(5, TimeUnit.SECONDS) shouldBe true }
          withClue("thrown: $thrown") { (thrown is EtcdRecipeRuntimeException) shouldBe true }
        } finally {
          contend.store(false)
          done.await(10, TimeUnit.SECONDS)
          rival.close()
        }
        DistributedAtomicLong.delete(etcd, path)
      }
    }

    "the compare-and-set retry backoff is capped" {
      for (attempt in listOf(1, 10, 100, 10_000, Int.MAX_VALUE)) {
        withClue("attempt $attempt") { DistributedAtomicLong.retryBackoff(attempt) shouldBeLessThanOrEqualTo 1.seconds }
      }
    }

    "withDistributedAtomicLong accepts a resilience config" {
      connectToEtcd(urls) { client ->
        val path = "$base/factory-resilience"
        DistributedAtomicLong.delete(client, path)
        withDistributedAtomicLong(client, path, resilience = ResilienceConfig.DISABLED) { increment() } shouldBe 1L
        DistributedAtomicLong.delete(client, path)
      }
    }
  }
}
