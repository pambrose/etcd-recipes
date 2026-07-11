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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Guards the ServiceInstance wire format against a subtle round-trip corruption:
 * registrationTimeUTC's default is `Instant.now()`, so with encodeDefaults=false
 * kotlinx-serialization OMITS the field whenever serialization runs in the same
 * clock millisecond as construction — and a later parse then back-fills a fresh
 * (different) timestamp. Every field must round-trip byte-identically no matter
 * when toJson() is called relative to construction.
 */
class ServiceInstanceJsonTests : StringSpec() {
  init {
    "toJson always encodes registrationTimeUTC" {
      // Tight loop: construction and serialization land in the same millisecond
      // on almost every iteration, which is exactly the case that used to omit
      // the field.
      repeat(1_000) {
        val instance = ServiceInstance("svc", "payload")
        instance.toJson() shouldContain "\"registrationTimeUTC\""
      }
    }

    "instances round-trip identically regardless of serialization timing" {
      repeat(1_000) {
        val instance = ServiceInstance("svc", "payload")
        ServiceInstance.toObject(instance.toJson()) shouldBe instance
      }
    }
  }
}
