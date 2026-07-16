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

package io.etcd.recipes.discovery

import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.jsonCodec
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable

/**
 * The typed ServiceInstance payload extensions: a value round-trips through the opaque `jsonPayload`
 * (which stays the unchanged wire form), and survives the full ServiceInstance JSON round-trip.
 */
class TypedServiceInstanceTests : StringSpec() {
  @Serializable
  data class Meta(
    val region: String,
    val weight: Int,
  )

  init {
    "serviceInstance builds an instance whose payload decodes back" {
      val codec = jsonCodec<Meta>()
      val meta = Meta("us-east", 5)
      val si = serviceInstance("svc", meta, codec)
      si.payload(codec) shouldBe meta
    }

    "payload survives the full ServiceInstance JSON wire round-trip" {
      val codec = jsonCodec<Meta>()
      val meta = Meta("eu-west", 9)
      val si = serviceInstance("svc", meta, codec) { apply { port = 8080 } }
      val roundTripped = ServiceInstance.toObject(si.toJson())
      roundTripped.payload(codec) shouldBe meta
      roundTripped.port shouldBe 8080
    }

    "setPayload replaces jsonPayload in place" {
      val codec = jsonCodec<Meta>()
      val si = serviceInstance("svc", Meta("a", 1), codec)
      si.setPayload(Meta("b", 2), codec)
      si.payload(codec) shouldBe Meta("b", 2)
    }

    "payload reads a raw jsonPayload through StringCodec" {
      val si = ServiceInstance("svc", "hello")
      si.payload(StringCodec) shouldBe "hello"
    }
  }
}
