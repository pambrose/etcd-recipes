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

import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class NodeCacheTests : StringSpec() {
  private val base = "/cache/${javaClass.simpleName}"

  @Serializable
  data class Config(
    val name: String,
    val count: Int,
  )

  init {
    "current reflects the initial value, then UPDATED and DELETED events fire" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val key = "$base/node"
        client.putValue(key, "v1")
        val events = CopyOnWriteArrayList<NodeCacheEvent<String>>()

        NodeCache(client, key, StringCodec).use { cache ->
          cache.addListener { events += it }
          cache.start()
          cache.current shouldBe "v1"

          client.putValue(key, "v2")
          pollUntil(10.seconds) { events.any { it.type == NodeCacheEvent.Type.UPDATED } } shouldBe true
          cache.current shouldBe "v2"

          client.deleteKey(key)
          pollUntil(10.seconds) { events.any { it.type == NodeCacheEvent.Type.DELETED } } shouldBe true
          cache.current shouldBe null
        }
      }
    }

    "a key that appears after start fires CREATED" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val key = "$base/late"
        val events = CopyOnWriteArrayList<NodeCacheEvent<String>>()

        NodeCache(client, key, StringCodec).use { cache ->
          cache.addListener { events += it }
          cache.start()
          cache.current shouldBe null

          client.putValue(key, "hello")
          pollUntil(10.seconds) { events.any { it.type == NodeCacheEvent.Type.CREATED } } shouldBe true
          cache.current shouldBe "hello"
        }
      }
    }

    "a JSON-typed node cache round-trips a data class" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val key = "$base/json"
        val codec = jsonCodec<Config>()
        client.putValue(key, codec.encode(Config("svc", 1)))

        NodeCache(client, key, codec).use { cache ->
          cache.start()
          cache.current shouldBe Config("svc", 1)
        }
      }
    }
  }
}
