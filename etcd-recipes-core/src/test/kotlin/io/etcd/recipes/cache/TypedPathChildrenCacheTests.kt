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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The typed prefix cache: the initial snapshot and live child events decode through a codec into
 * [TypedChildData] / [TypedPathChildrenCacheEvent].
 */
class TypedPathChildrenCacheTests : StringSpec() {
  private val base = "/cache/${javaClass.simpleName}"

  @Serializable
  data class Node(
    val n: Int,
  )

  init {
    "typed currentData and getCurrentData decode the initial snapshot" {
      connectToEtcd(urls) { client ->
        val path = "$base/snapshot"
        val codec = jsonCodec<Node>()
        client.deleteChildren(path)
        client.putValue("$path/a", Node(1), codec)
        client.putValue("$path/b", Node(2), codec)

        TypedPathChildrenCache(client, path, codec).use { cache ->
          cache.start(buildInitial = true)
          cache.currentData shouldContainExactlyInAnyOrder
            [TypedChildData("a", Node(1)), TypedChildData("b", Node(2))]
          cache.getCurrentData("a") shouldBe Node(1)
          cache.currentDataAsMap shouldBe mapOf("a" to Node(1), "b" to Node(2))
        }
      }
    }

    "typed listener receives decoded CHILD_ADDED, CHILD_UPDATED, and CHILD_REMOVED" {
      connectToEtcd(urls) { client ->
        val path = "$base/events"
        val codec = jsonCodec<Node>()
        client.deleteChildren(path)
        val events = CopyOnWriteArrayList<TypedPathChildrenCacheEvent<Node>>()

        TypedPathChildrenCache(client, path, codec).use { cache ->
          cache.addListener { events += it }
          cache.start(buildInitial = false)

          client.putValue("$path/k", Node(1), codec)
          pollUntil(10.seconds) { events.any { it.type == PathChildrenCacheEvent.Type.CHILD_ADDED } } shouldBe true
          events.first { it.type == PathChildrenCacheEvent.Type.CHILD_ADDED }.data shouldBe Node(1)

          client.putValue("$path/k", Node(2), codec)
          pollUntil(10.seconds) { events.any { it.type == PathChildrenCacheEvent.Type.CHILD_UPDATED } } shouldBe true
          events.first { it.type == PathChildrenCacheEvent.Type.CHILD_UPDATED }.data shouldBe Node(2)

          client.deleteKey("$path/k")
          pollUntil(10.seconds) { events.any { it.type == PathChildrenCacheEvent.Type.CHILD_REMOVED } } shouldBe true
        }
      }
    }
  }
}
