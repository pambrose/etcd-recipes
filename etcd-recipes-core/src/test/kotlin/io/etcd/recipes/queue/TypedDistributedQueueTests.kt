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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable

/**
 * The typed queue wrappers: values round-trip through a codec, FIFO order is preserved, and the
 * priority variant still dequeues by priority.
 */
class TypedDistributedQueueTests : StringSpec() {
  private val base = "/queue/${javaClass.simpleName}"

  @Serializable
  data class Msg(
    val id: Int,
    val body: String,
  )

  init {
    "typed queue round-trips a value through a codec" {
      connectToEtcd(urls) { client ->
        val path = "$base/roundtrip"
        client.deleteChildren(path)
        TypedDistributedQueue(client, path, jsonCodec<Msg>()).use { queue ->
          val msg = Msg(1, "hello")
          queue.enqueue(msg)
          queue.dequeue() shouldBe msg
        }
      }
    }

    "typed queue preserves FIFO order across enqueue and enqueueAll" {
      connectToEtcd(urls) { client ->
        val path = "$base/fifo"
        client.deleteChildren(path)
        TypedDistributedQueue(client, path, jsonCodec<Msg>()).use { queue ->
          queue.enqueue(Msg(1, "a"))
          queue.enqueueAll(listOf(Msg(2, "b"), Msg(3, "c")))
          queue.dequeue() shouldBe Msg(1, "a")
          queue.dequeue() shouldBe Msg(2, "b")
          queue.dequeue() shouldBe Msg(3, "c")
        }
      }
    }

    "typed priority queue dequeues by ascending priority" {
      connectToEtcd(urls) { client ->
        val path = "$base/priority"
        client.deleteChildren(path)
        TypedDistributedPriorityQueue(client, path, jsonCodec<Msg>()).use { queue ->
          queue.enqueue(Msg(5, "low"), priority = 5)
          queue.enqueue(Msg(1, "high"), priority = 1)
          queue.enqueue(Msg(3, "mid"), priority = 3)
          queue.dequeue() shouldBe Msg(1, "high")
          queue.dequeue() shouldBe Msg(3, "mid")
          queue.dequeue() shouldBe Msg(5, "low")
        }
      }
    }
  }
}
