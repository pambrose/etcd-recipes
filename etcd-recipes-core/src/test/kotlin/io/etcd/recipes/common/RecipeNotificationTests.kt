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

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Watch
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.WatchOption
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.discovery.ServiceProvider
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.lock.DistributedMutex
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Recipe-level halves of the notification and health fixes:
 *
 * - a lock-lost listener runs on the recipe's notifier, not on jetcd's lease callback thread;
 * - composite recipes (`LeaderLatch`, `ServiceProvider`) report the failure of a recipe
 *   they wrap;
 * - `close()` called from a cache listener (the watch dispatcher) returns promptly.
 */
class RecipeNotificationTests : StringSpec() {
  private val base = "/common/${javaClass.simpleName}"

  private fun revokeLeaseOf(
    client: Client,
    key: String,
  ) {
    client.leaseClient.revoke(client.getResponse(key).kvs.single().lease).get()
  }

  init {
    "a lock-lost listener runs on the recipe's notifier, not on jetcd's lease thread" {
      connectToEtcd(urls) { client ->
        val path = "$base/lock-lost"
        client.deleteChildren(path)
        DistributedMutex(client, path).use { mutex ->
          val lostOn = AtomicReference<String?>(null)
          mutex.addLockLostListener { lostOn.store(Thread.currentThread().name) }
          val held = CountDownLatch(1)
          val holder =
            thread {
              mutex.lock()
              held.countDown()
              runCatching { Thread.sleep(30_000) }
            }
          held.awaitOrFail(10.seconds, "the lock")
          revokeLeaseOf(client, client.getChildrenKeys(path).single())
          pollUntil(20.seconds) { lostOn.load() != null } shouldBe true
          lostOn.load()!! shouldStartWith "etcd-recipe-notifier"
          holder.interrupt()
          holder.join(10_000)
        }
        client.deleteChildren(path)
      }
    }

    "a standby LeaderLatch reports its participation lease's abandoned heal" {
      connectToEtcd(urls) { client ->
        val path = "$base/latch"
        client.deleteChildren(path)
        LeaderLatch(client, path, clientId = "leader").start().use { leader ->
          leader.await(10.seconds) shouldBe true
          val noHeal = ResilienceConfig(lease = LeaseResilience.DISABLED)
          LeaderLatch(client, path, clientId = "standby", resilience = noHeal).start().use { standby ->
            val participation = "$path/participants/standby"
            pollUntil(10.seconds) { client.getValue(participation) != null } shouldBe true
            revokeLeaseOf(client, participation)
            withClue("the latch hid its selector's dead participation lease") {
              pollUntil(20.seconds) { standby.connectionState == ConnectionState.LOST } shouldBe true
            }
            standby.isHealthy() shouldBe false
          }
        }
        client.deleteChildren(path)
      }
    }

    "a ServiceProvider reports its cache's abandoned watch" {
      val listeners = CopyOnWriteArrayList<Watch.Listener>()
      val client =
        mockk<Client> {
          every { kvClient } returns
            mockk<KV> {
              every { get(any<ByteSequence>(), any()) } answers {
                CompletableFuture.completedFuture(
                  mockk<GetResponse> {
                    every { kvs } returns emptyList()
                    every { isMore } returns false
                    every { header } returns mockk { every { revision } returns 10L }
                  },
                )
              }
            }
          every { watchClient } returns
            mockk<Watch> {
              every { watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) } answers {
                listeners += thirdArg<Watch.Listener>()
                mockk<Watch.Watcher>(relaxed = true)
              }
            }
        }
      val noRecovery = ResilienceConfig(watch = WatchResilience.DISABLED)
      ServiceProvider(client, "/discovery/forwarding", "svc", resilience = noRecovery).start().use { provider ->
        listeners.single().onError(RuntimeException("fatal watch error"))
        listeners.single().onCompleted()
        withClue("the provider hid its cache's abandoned watch") {
          pollUntil(10.seconds) { provider.connectionState == ConnectionState.LOST } shouldBe true
        }
        provider.isHealthy() shouldBe false
        provider.exceptions.isNotEmpty() shouldBe true
      }
    }

    "close() from a cache listener returns promptly" {
      connectToEtcd(urls) { client ->
        val path = "$base/close-from-listener"
        client.deleteChildren(path)
        val cache = PathChildrenCache(client, path).start(true)
        val closedIn = AtomicReference<kotlin.time.Duration?>(null)
        cache.addListener {
          val start = TimeSource.Monotonic.markNow()
          cache.close()
          closedIn.store(start.elapsedNow())
        }
        client.putValue("$path/a", "1")
        pollUntil(15.seconds) { closedIn.load() != null } shouldBe true
        withClue("close() waited on its own dispatcher: ${closedIn.load()}") {
          (closedIn.load()!! < 2.seconds) shouldBe true
        }
        client.deleteChildren(path)
      }
    }
  }
}
