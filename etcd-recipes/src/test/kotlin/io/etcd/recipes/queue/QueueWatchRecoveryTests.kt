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

package io.etcd.recipes.queue

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.Txn
import io.etcd.jetcd.Watch
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.options.WatchOption
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.pollUntil
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a parked [AbstractQueue.dequeue] through a fatal watch death with a mocked
 * jetcd [Client]. An item that arrived while the watch stream was dead must be
 * delivered after recovery (the recovery re-poll), and an abandoned recovery must
 * fail the dequeue instead of parking it forever.
 */
class QueueWatchRecoveryTests : StringSpec() {
  private class QueueMocks(
    /** GET calls up to this count return an empty queue; later ones return the item. */
    private val emptyGets: Int,
  ) {
    val listeners = CopyOnWriteArrayList<Watch.Listener>()
    val getCount = AtomicInteger(0)

    private val item: KeyValue =
      mockk {
        every { key } returns "/queue/recovery/0001".asByteSequence
        every { value } returns "hello".asByteSequence
        every { modRevision } returns 5L
      }

    private fun getResponse(): GetResponse {
      val empty = getCount.incrementAndGet() <= emptyGets
      return mockk {
        every { kvs } returns if (empty) emptyList() else listOf(item)
        every { isMore } returns false
      }
    }

    val client: Client =
      mockk {
        every { kvClient } returns
          mockk<KV> {
            every { get(any<ByteSequence>(), any()) } answers {
              CompletableFuture.completedFuture(getResponse())
            }
            every { txn() } returns
              mockk<Txn> {
                every { If(*anyVararg()) } returns this
                every { Then(*anyVararg()) } returns this
                every { commit() } returns
                  CompletableFuture.completedFuture(mockk<TxnResponse> { every { isSucceeded } returns true })
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
  }

  private fun Watch.Listener.die() {
    onError(RuntimeException("fatal watch error"))
    onCompleted()
  }

  init {
    "parked dequeue delivers an item that arrived while the watch stream was dead" {
      // GET #1 (fast path) and #2 (pre-live gap poll) see an empty queue; the
      // recovery re-poll and everything after see the item.
      val mocks = QueueMocks(emptyGets = 2)
      val result = AtomicReference<ByteSequence?>()
      val error = AtomicReference<Throwable?>()

      DistributedQueue(mocks.client, "/queue/recovery").use { queue ->
        val worker =
          thread(name = "dequeue-under-test") {
            try {
              result.set(queue.dequeue())
            } catch (e: Throwable) {
              error.set(e)
            }
          }

        pollUntil(5.seconds) { mocks.listeners.size == 1 } shouldBe true
        mocks.listeners.first().die()

        pollUntil(10.seconds) { result.get() != null || error.get() != null } shouldBe true
        error.get() shouldBe null
        result.get()!!.asString shouldBe "hello"
        worker.join(5_000)
      }
    }

    "parked dequeue fails fast when watch recovery is abandoned" {
      // Queue stays empty forever and recovery is disabled: the dequeue must
      // surface an error instead of parking forever.
      val mocks = QueueMocks(emptyGets = Int.MAX_VALUE)
      val error = AtomicReference<Throwable?>()
      val result = AtomicReference<ByteSequence?>()

      DistributedQueue(mocks.client, "/queue/recovery", ResilienceConfig.DISABLED).use { queue ->
        val worker =
          thread(name = "dequeue-under-test") {
            try {
              result.set(queue.dequeue())
            } catch (e: Throwable) {
              error.set(e)
            }
          }

        pollUntil(5.seconds) { mocks.listeners.size == 1 } shouldBe true
        mocks.listeners.first().die()

        pollUntil(10.seconds) { error.get() != null } shouldBe true
        error.get().shouldBeInstanceOf<EtcdRecipeRuntimeException>()
        result.get() shouldBe null
        worker.join(5_000)
      }
    }
  }
}
