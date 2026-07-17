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

package io.etcd.recipes.discovery

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.Watch
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.WatchOption
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.pollUntil
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [ServiceCache] through a compaction-killed watch with a mocked jetcd
 * [Client]: the cache must reconcile its instance map from a fresh snapshot and
 * re-anchor the replacement watch, so [ServiceCache.instances] converges despite
 * the lost history. (ServiceCache had no manual rebuild at all before resilience.)
 */
class ServiceCacheResyncTests : StringSpec() {
  private val namesPath = "/discovery/resync"
  private val serviceName = "svc"

  private val v1 = ServiceInstance(serviceName, "payload-1")
  private val v1Updated = v1.copy(jsonPayload = "payload-1-updated")
  private val v2 = ServiceInstance(serviceName, "payload-2")

  private inner class CacheMocks {
    val listeners = CopyOnWriteArrayList<Watch.Listener>()
    val options = CopyOnWriteArrayList<WatchOption>()
    private val getCount = AtomicInt(0)

    private fun kv(
      id: String,
      json: String,
    ): KeyValue =
      mockk {
        every { key } returns "$namesPath/$serviceName/$id".asByteSequence
        every { value } returns json.asByteSequence
      }

    // GET #1 (initial snapshot): {v1} at revision 10. Every later GET (resync
    // snapshot): {v1Updated, v2} at revision 20.
    private fun getResponse(): GetResponse {
      val first = getCount.incrementAndFetch() == 1
      return mockk {
        every { kvs } returns
          if (first) {
            listOf(kv(v1.id, v1.toJson()))
          } else {
            listOf(kv(v1.id, v1Updated.toJson()), kv(v2.id, v2.toJson()))
          }
        every { isMore } returns false
        every { header } returns mockk { every { revision } returns if (first) 10L else 20L }
      }
    }

    val client: Client =
      mockk {
        every { kvClient } returns
          mockk<KV> {
            every { get(any<ByteSequence>(), any()) } answers {
              CompletableFuture.completedFuture(getResponse())
            }
          }
        every { watchClient } returns
          mockk<Watch> {
            every { watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) } answers {
              options += secondArg<WatchOption>()
              listeners += thirdArg<Watch.Listener>()
              mockk<Watch.Watcher>(relaxed = true)
            }
          }
      }
  }

  init {
    "compaction-killed service cache watch reconciles instances and re-anchors" {
      val mocks = CacheMocks()
      val recovery = CopyOnWriteArrayList<WatchRecoveryEvent>()

      ServiceCache(mocks.client, namesPath, serviceName).use { cache ->
        cache.addRecoveryListener { recovery += it }
        cache.start()

        cache.instances.map { it.jsonPayload } shouldBe listOf("payload-1")
        mocks.options.first().revision shouldBe 11 // snapshot revision + 1

        // etcd compacted away the watch anchor: jetcd reports a fatal death
        mocks.listeners.first().onError(EtcdExceptionFactory.newCompactedException(15))
        mocks.listeners.first().onCompleted()

        pollUntil(10.seconds) { recovery.any { it is WatchRecoveryEvent.Resynced } } shouldBe true
        pollUntil(10.seconds) {
          mocks.options.size == 2 && mocks.options[1].revision == 21L
        } shouldBe true
        cache.instances.map { it.jsonPayload }.sorted() shouldBe listOf("payload-1-updated", "payload-2")
      }
    }
  }
}
