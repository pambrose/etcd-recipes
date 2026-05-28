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

/**
 * Unit tests for the Java-style [ServiceInstance] builder and the Kotlin
 * `serviceInstance { }` DSL. No etcd needed.
 */
class ServiceInstanceBuilderTests : StringSpec() {
    init {
        "newBuilder().build() carries every configured field through" {
            val instance =
                ServiceInstance.newBuilder("TestName", """{"v":1}""")
                    .apply {
                        address = "host"
                        port = 1234
                        sslPort = 5678
                        registrationTimeUTC = 42L
                        serviceType = ServiceType.STATIC
                        uri = "http://host:1234"
                        enabled = false
                    }
                    .build()

            instance.name shouldBe "TestName"
            instance.jsonPayload shouldBe """{"v":1}"""
            instance.address shouldBe "host"
            instance.port shouldBe 1234
            instance.sslPort shouldBe 5678
            instance.registrationTimeUTC shouldBe 42L
            instance.serviceType shouldBe ServiceType.STATIC
            instance.uri shouldBe "http://host:1234"
            instance.enabled shouldBe false
        }

        "build() applies defaults when only required fields are set" {
            val instance = ServiceInstance.newBuilder("TestName", "{}").build()

            instance.address shouldBe ""
            instance.port shouldBe -1
            instance.sslPort shouldBe -1
            instance.serviceType shouldBe ServiceType.DYNAMIC
            instance.uri shouldBe ""
            instance.enabled shouldBe true
        }

        "serviceInstance DSL builds an equivalent instance" {
            val viaDsl =
                serviceInstance("TestName", "{}") {
                    address = "host"
                    port = 1234
                    this
                }

            viaDsl.name shouldBe "TestName"
            viaDsl.address shouldBe "host"
            viaDsl.port shouldBe 1234
        }
    }
}
