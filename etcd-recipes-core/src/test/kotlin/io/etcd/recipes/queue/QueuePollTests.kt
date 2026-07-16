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

import io.etcd.jetcd.options.GetOption
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getKeyValuePairs
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Bounded and non-blocking consumption on the existing queues: the pre-0.12 API
 * offered only a forever-blocking take, so consumers could not opt out of waiting
 * or bound their wait. Also covers atomic batch enqueue.
 */
class QueuePollTests : StringSpec() {
  private val base = "/queue/${javaClass.simpleName}"

  init {
    "tryDequeue returns null on an empty queue" {
      connectToEtcd(urls) { client ->
        client.deleteChildren("$base/try-empty")
        DistributedQueue(client, "$base/try-empty").use { queue ->
          queue.tryDequeue().shouldBeNull()
        }
      }
    }

    "tryDequeue takes items in FIFO order then reports empty" {
      connectToEtcd(urls) { client ->
        client.deleteChildren("$base/try-fifo")
        DistributedQueue(client, "$base/try-fifo").use { queue ->
          queue.enqueue("first")
          queue.enqueue("second")
          queue.tryDequeue()?.asString shouldBe "first"
          queue.tryDequeue()?.asString shouldBe "second"
          queue.tryDequeue().shouldBeNull()
        }
      }
    }

    "poll returns null after the timeout on an empty queue" {
      connectToEtcd(urls) { client ->
        client.deleteChildren("$base/poll-timeout")
        DistributedQueue(client, "$base/poll-timeout").use { queue ->
          val start = TimeSource.Monotonic.markNow()
          queue.poll(2.seconds).shouldBeNull()
          (start.elapsedNow() >= 1.5.seconds) shouldBe true
          (start.elapsedNow() < 30.seconds) shouldBe true
        }
      }
    }

    "poll returns an item enqueued while waiting" {
      connectToEtcd(urls) { client ->
        client.deleteChildren("$base/poll-late")
        DistributedQueue(client, "$base/poll-late").use { queue ->
          val producer =
            thread {
              Thread.sleep(1_000)
              connectToEtcd(urls) { p ->
                DistributedQueue(p, "$base/poll-late").use { it.enqueue("late-arrival") }
              }
            }
          val item = queue.poll(30.seconds)
          item.shouldNotBeNull()
          item.asString shouldBe "late-arrival"
          producer.join(5_000)
        }
      }
    }

    "poll on the priority queue respects priority order" {
      connectToEtcd(urls) { client ->
        client.deleteChildren("$base/poll-priority")
        DistributedPriorityQueue(client, "$base/poll-priority", 0.seconds).use { queue ->
          queue.enqueue("low", 9)
          queue.enqueue("high", 1)
          queue.poll(10.seconds)?.asString shouldBe "high"
          queue.poll(10.seconds)?.asString shouldBe "low"
          queue.tryDequeue().shouldBeNull()
        }
      }
    }

    "enqueueAll commits atomically and preserves argument order" {
      connectToEtcd(urls) { client ->
        client.deleteChildren("$base/batch")
        DistributedQueue(client, "$base/batch").use { queue ->
          queue.enqueueAll(listOf("a", "b", "c").map { it.asByteSequence })

          // one transaction => every entry shares the same mod revision
          val option: GetOption = getOption { isPrefix(true) }
          val pairs = client.getKeyValuePairs("$base/batch/", option)
          pairs shouldHaveSize 3
          val revisions =
            client.getResponse("$base/batch/", getOption { isPrefix(true) }).kvs.map { it.modRevision }.toSet()
          revisions shouldHaveSize 1

          queue.tryDequeue()?.asString shouldBe "a"
          queue.tryDequeue()?.asString shouldBe "b"
          queue.tryDequeue()?.asString shouldBe "c"
        }
      }
    }
  }
}
