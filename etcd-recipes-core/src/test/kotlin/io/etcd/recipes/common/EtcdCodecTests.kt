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
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable

class EtcdCodecTests : StringSpec() {
  @Serializable
  data class Config(
    val name: String,
    val count: Int,
  )

  init {
    "StringCodec round-trips" {
      StringCodec.decode(StringCodec.encode("hello")) shouldBe "hello"
    }

    "ByteSequenceCodec is the identity" {
      val bytes = "raw".asByteSequence
      ByteSequenceCodec.encode(bytes) shouldBe bytes
      ByteSequenceCodec.decode(bytes) shouldBe bytes
    }

    "jsonCodec round-trips a @Serializable type" {
      val codec = jsonCodec<Config>()
      val value = Config("svc", 3)
      codec.decode(codec.encode(value)) shouldBe value
    }

    "jsonCodec encodes to JSON bytes" {
      jsonCodec<Config>().encode(Config("a", 1)).asString shouldBe """{"name":"a","count":1}"""
    }
  }
}
