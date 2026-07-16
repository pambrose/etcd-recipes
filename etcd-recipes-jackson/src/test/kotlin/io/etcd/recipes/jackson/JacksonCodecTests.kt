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

package io.etcd.recipes.jackson

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import io.etcd.recipes.common.asString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The Jackson backend for [io.etcd.recipes.common.EtcdCodec], verified with a vanilla
 * [com.fasterxml.jackson.databind.ObjectMapper]. The `@JsonCreator` annotation keeps the sample
 * type constructable without the optional `jackson-module-kotlin`, mirroring a Java POJO.
 */
class JacksonCodecTests : StringSpec() {
  data class Point
    @JsonCreator
    constructor(
      @param:JsonProperty("x") val x: Int,
      @param:JsonProperty("y") val y: Int,
    )

  init {
    "JacksonCodec round-trips via a Class token" {
      val codec = JacksonCodec(Point::class.java)
      val value = Point(1, 2)
      codec.decode(codec.encode(value)) shouldBe value
    }

    "jacksonCodec reified helper round-trips" {
      val codec = jacksonCodec<Point>()
      val value = Point(3, 4)
      codec.decode(codec.encode(value)) shouldBe value
    }

    "JacksonCodec round-trips a generic type via TypeReference" {
      val codec = JacksonCodec(object : TypeReference<List<Point>>() {})
      val value = listOf(Point(1, 1), Point(2, 2))
      codec.decode(codec.encode(value)) shouldBe value
    }

    "JacksonCodec encodes to JSON bytes" {
      JacksonCodec(Point::class.java).encode(Point(5, 6)).asString shouldBe """{"x":5,"y":6}"""
    }
  }
}
