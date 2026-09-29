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

import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Queue keys must stay inside their own queue and must never collide:
 *
 * - a parked take watches its own children only, so an item written to a sibling queue
 *   whose path merely shares the string prefix (`/q1` vs `/q10`) is not stolen;
 * - an item key is created only if absent, retrying with a fresh key, so an enqueue can
 *   never silently overwrite a queued item;
 * - a failed enqueue write is surfaced, not retried, so it cannot re-create an item a
 *   consumer already took;
 * - an infinite delay is rejected, and a malformed delayed key is dead-lettered instead
 *   of breaking every receive.
 */
class QueueKeyHygieneTests : StringSpec() {
  private val base = "/queue/${javaClass.simpleName}"

  init {
    "a parked take ignores items in a sibling queue whose path shares its prefix" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/sibling"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)

        DistributedQueue(client, "$path/q1").use { q1 ->
          DistributedQueue(etcd, "$path/q10").use { q10 ->
            // Lands the sibling's item after q1's consumer has found its own queue
            // empty and put its watch up.
            client.afterWatch.store { q10.enqueue("sibling-item") }

            q1.poll(2.seconds).shouldBeNull()
            q10.tryDequeue()?.asString shouldBe "sibling-item"
          }
        }
        etcd.deleteChildren(path)
      }
    }

    "an item key is created only if it does not already exist" {
      connectToEtcd(urls) { client ->
        val path = "$base/create-only"
        client.deleteChildren(path)
        client.putValue("$path/taken", "original")
        val keys = ArrayDeque(listOf("$path/taken", "$path/fresh"))

        val key = client.createUniqueKey("new".asByteSequence, RpcResilience.DEFAULT) { keys.removeFirst() }

        key shouldBe "$path/fresh"
        client.getValue("$path/taken", "") shouldBe "original"
        client.getValue("$path/fresh", "") shouldBe "new"
        client.deleteChildren(path)
      }
    }

    "a batch is created all-or-nothing, retrying with fresh keys if any already exists" {
      connectToEtcd(urls) { client ->
        val path = "$base/create-only-batch"
        client.deleteChildren(path)
        client.putValue("$path/b", "original")
        val keySets = ArrayDeque(listOf(listOf("$path/a", "$path/b"), listOf("$path/c", "$path/d")))
        val values = listOf("1".asByteSequence, "2".asByteSequence)

        val keys = client.createUniqueKeys(values, RpcResilience.DEFAULT) { keySets.removeFirst() }

        keys shouldBe listOf("$path/c", "$path/d")
        client.getValue("$path/a").shouldBeNull()
        client.getValue("$path/b", "") shouldBe "original"
        client.getValue("$path/c", "") shouldBe "1"
        client.getValue("$path/d", "") shouldBe "2"
        client.deleteChildren(path)
      }
    }

    "a failed enqueue write is surfaced, not retried" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/no-retry"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)

        // A retry after an ambiguous failure could re-create an item that a consumer
        // already took, so the failure must reach the caller with nothing written.
        DistributedQueue(client, "$path/queue").use { queue ->
          client.beforeTxn.store { throw IllegalStateException("injected write failure") }
          shouldThrow<IllegalStateException> { queue.enqueue("item") }
          queue.size shouldBe 0
        }
        DistributedWorkQueue(client, "$path/work").use { queue ->
          client.beforeTxn.store { throw IllegalStateException("injected write failure") }
          shouldThrow<IllegalStateException> { queue.enqueue("item") }
          etcd.getChildCount("$path/work/items") shouldBe 0L
        }
        etcd.deleteChildren(path)
      }
    }

    "an infinite delay is rejected instead of writing a poison key" {
      connectToEtcd(urls) { client ->
        val path = "$base/infinite-delay"
        client.deleteChildren(path)

        DistributedWorkQueue(client, path).use { queue ->
          shouldThrow<IllegalArgumentException> { queue.enqueue("x", Duration.INFINITE) }
          client.getChildCount("$path/delayed") shouldBe 0L
        }
        client.deleteChildren(path)
      }
    }

    "a malformed delayed key is dead-lettered instead of breaking receive" {
      connectToEtcd(urls) { client ->
        val path = "$base/malformed-delayed"
        client.deleteChildren(path)
        // What an overflowed delay used to write. '-' sorts before any digit, so it is
        // the permanent head of delayed/.
        client.putValue("$path/delayed/-9223372036854775808-abc", "poison")

        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("ok")
          queue.tryReceive()?.value?.asString shouldBe "ok"
          queue.deadLetters().map { it.id } shouldBe listOf("-9223372036854775808-abc")
          queue.exceptions.shouldNotBeEmpty()
        }
        client.deleteChildren(path)
      }
    }
  }
}
