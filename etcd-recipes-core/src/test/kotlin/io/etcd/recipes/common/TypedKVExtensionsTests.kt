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

package io.etcd.recipes.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable

/**
 * The typed [Client.putValue] / [Client.getValue] extensions: a value round-trips through any
 * [EtcdCodec], and a read of an absent key is null.
 */
class TypedKVExtensionsTests : StringSpec() {
  private val base = "/common/${javaClass.simpleName}"

  @Serializable
  data class Config(
    val name: String,
    val count: Int,
  )

  init {
    "putValue and getValue round-trip a value through a JSON codec" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val key = "$base/config"
        val codec = jsonCodec<Config>()
        val value = Config("svc", 7)
        client.putValue(key, value, codec)
        client.getValue(key, codec) shouldBe value
      }
    }

    "getValue returns null for an absent key" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        client.getValue("$base/missing", jsonCodec<Config>()).shouldBeNull()
      }
    }

    "putValue and getValue round-trip via StringCodec" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val key = "$base/str"
        client.putValue(key, "hello", StringCodec)
        client.getValue(key, StringCodec) shouldBe "hello"
      }
    }

    "putValue and getValue round-trip via ByteSequenceCodec" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val key = "$base/bytes"
        val bytes = "raw".asByteSequence
        client.putValue(key, bytes, ByteSequenceCodec)
        client.getValue(key, ByteSequenceCodec) shouldBe bytes
      }
    }
  }
}
