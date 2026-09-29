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

import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.awaitOrFail
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.urls
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * `close()` releases a `DistributedQueue` consumer parked on an empty queue, the way it
 * releases barrier waiters. The take fails instead of parking until an item arrives, and
 * an item that arrives later is not deleted and handed to the closed instance.
 */
class QueueCloseTests : StringSpec() {
  private val base = "/queue/${javaClass.simpleName}"

  private fun closeWakesParkedTake(
    name: String,
    take: (DistributedQueue) -> Any?,
  ) {
    connectToEtcd(urls) { etcd ->
      val path = "$base/$name"
      etcd.deleteChildren(path)
      val client = HookedClient(etcd)
      val queue = DistributedQueue(client, path)
      val parked = CountDownLatch(1)
      client.afterWatch.store { parked.countDown() }
      var error: Throwable? = null
      val done = CountDownLatch(1)
      thread(isDaemon = true) {
        try {
          take(queue)
        } catch (e: Throwable) {
          error = e
        } finally {
          done.countDown()
        }
      }
      parked.awaitOrFail(10.seconds, "the parked take")
      queue.close()
      withClue("close() did not wake the parked take") { done.await(5, TimeUnit.SECONDS) shouldBe true }
      withClue("error=$error") { (error is EtcdRecipeRuntimeException) shouldBe true }

      DistributedQueue(etcd, path).use { other ->
        other.enqueue("after-close")
        other.tryDequeue()?.asString shouldBe "after-close"
      }
      etcd.deleteChildren(path)
    }
  }

  init {
    "close() wakes an unbounded dequeue parked on an empty queue" {
      closeWakesParkedTake("dequeue") { it.dequeue() }
    }

    "close() wakes a bounded poll parked on an empty queue" {
      closeWakesParkedTake("poll") { it.poll(60.seconds) }
    }
  }
}
