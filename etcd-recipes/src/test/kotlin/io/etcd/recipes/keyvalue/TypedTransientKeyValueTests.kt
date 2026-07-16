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

package io.etcd.recipes.keyvalue

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/**
 * The typed transient key/value: the value is encoded once through a codec and published under the
 * lease, readable back through the typed [getValue].
 */
class TypedTransientKeyValueTests : StringSpec() {
  private val base = "/keyvalue/${javaClass.simpleName}"

  @Serializable
  data class Reg(
    val host: String,
    val port: Int,
  )

  init {
    "typed transient key publishes an encoded value readable through the codec" {
      connectToEtcd(urls) { client ->
        val key = "$base/reg"
        client.deleteKey(key)
        val codec = jsonCodec<Reg>()
        val reg = Reg("h1", 8080)
        TypedTransientKeyValue(client, key, reg, codec).use { tkv ->
          pollUntil(10.seconds) { client.getValue(key, codec) == reg } shouldBe true
          tkv.untyped.isHealthy() shouldBe true
        }
      }
    }

    "typed transient key with autoStart=false publishes only after start()" {
      connectToEtcd(urls) { client ->
        val key = "$base/manual"
        client.deleteKey(key)
        val codec = jsonCodec<Reg>()
        val reg = Reg("h2", 9090)
        TypedTransientKeyValue(client, key, reg, codec, autoStart = false).use { tkv ->
          client.getValue(key, codec) shouldBe null
          tkv.start()
          pollUntil(10.seconds) { client.getValue(key, codec) == reg } shouldBe true
        }
      }
    }
  }
}
