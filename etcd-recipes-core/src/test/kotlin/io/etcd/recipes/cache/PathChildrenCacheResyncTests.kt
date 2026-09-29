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

package io.etcd.recipes.cache

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
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.pollUntil
import io.kotest.assertions.withClue
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
 * Drives [PathChildrenCache] through a compaction-killed watch with a mocked jetcd
 * [Client] (in the style of [io.etcd.recipes.common.FailingLeaseMocks]): the cache
 * must reconcile its map from a fresh snapshot and re-anchor the replacement watch
 * at the snapshot's revision, so `currentData` converges despite the lost history —
 * and tell its listeners what changed in the gap, since no watch event ever will.
 */
class PathChildrenCacheResyncTests : StringSpec() {
  private class CacheMocks(
    val cachePath: String,
  ) {
    val listeners = CopyOnWriteArrayList<Watch.Listener>()
    val options = CopyOnWriteArrayList<WatchOption>()
    private val getCount = AtomicInt(0)

    private fun kv(
      name: String,
      value: String,
    ): KeyValue =
      mockk {
        every { key } returns "$cachePath/$name".asByteSequence
        every { this@mockk.value } returns value.asByteSequence
      }

    // GET #1 (initial snapshot): {a=1, c=9} at revision 10. Every later GET (resync
    // snapshot): {a=2, b=3} at revision 20 — c removed, a updated, b added in the gap.
    private fun getResponse(): GetResponse {
      val first = getCount.incrementAndFetch() == 1
      return mockk {
        every { kvs } returns
          if (first) [kv("a", "1"), kv("c", "9")] else [kv("a", "2"), kv("b", "3")]
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
    "compaction-killed cache watch reconciles the map and re-anchors past the snapshot" {
      val mocks = CacheMocks("/cache/resync")
      val recovery = CopyOnWriteArrayList<WatchRecoveryEvent>()

      PathChildrenCache(mocks.client, mocks.cachePath).use { cache ->
        val events = CopyOnWriteArrayList<Triple<PathChildrenCacheEvent.Type, String, String?>>()
        cache.addListener { events += Triple(it.type, it.childName, it.data?.asString) }
        cache.addRecoveryListener { recovery += it }
        cache.start(true)

        cache.currentDataAsMap.mapValues { it.value.asString } shouldBe mapOf("a" to "1", "c" to "9")
        mocks.options.first().revision shouldBe 11 // snapshot revision + 1

        // etcd compacted away the watch anchor: jetcd reports a fatal death
        mocks.listeners.first().onError(EtcdExceptionFactory.newCompactedException(15))
        mocks.listeners.first().onCompleted()

        pollUntil(10.seconds) { recovery.any { it is WatchRecoveryEvent.Resynced } } shouldBe true
        val resync = recovery.filterIsInstance<WatchRecoveryEvent.Resynced>().first()
        resync.compactRevision shouldBe 15
        resync.anchorRevision shouldBe 21 // resync snapshot revision + 1

        pollUntil(10.seconds) {
          mocks.options.size == 2 && mocks.options[1].revision == 21L
        } shouldBe true
        cache.currentDataAsMap.mapValues { it.value.asString } shouldBe mapOf("a" to "2", "b" to "3")
        withClue("the resync changed the cache without telling its listeners") {
          events.toSet() shouldBe
            setOf(
              Triple(PathChildrenCacheEvent.Type.CHILD_REMOVED, "c", "9"),
              Triple(PathChildrenCacheEvent.Type.CHILD_UPDATED, "a", "2"),
              Triple(PathChildrenCacheEvent.Type.CHILD_ADDED, "b", "3"),
            )
          events.size shouldBe 3
        }
      }
    }
  }
}
