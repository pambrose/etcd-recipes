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
import kotlin.time.Duration.Companion.seconds

class KeepAliveExtensionsTests : StringSpec() {
    private val path = "/keepalive-extensions/${javaClass.simpleName}"
    private val ttl = 5L

    init {
        "putValueWithKeepAlive keeps each typed value live for the duration of the block" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)

                // ttlSecs (Long) overloads
                client.putValueWithKeepAlive("$path/str", "hello", ttl) {
                    client.getValue("$path/str")?.asString shouldBe "hello"
                }
                client.putValueWithKeepAlive("$path/int", 123, ttl) {
                    client.getValue("$path/int")?.asInt shouldBe 123
                }
                client.putValueWithKeepAlive("$path/long", 456L, ttl) {
                    client.getValue("$path/long")?.asLong shouldBe 456L
                }
                client.putValueWithKeepAlive("$path/bytes", "raw".asByteSequence, ttl) {
                    client.getValue("$path/bytes")?.asString shouldBe "raw"
                }

                client.deleteChildren(path)
            }
        }

        "putValueWithKeepAlive accepts a Duration ttl for each typed value" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)

                client.putValueWithKeepAlive("$path/dstr", "hello", ttl.seconds) {
                    client.getValue("$path/dstr")?.asString shouldBe "hello"
                }
                client.putValueWithKeepAlive("$path/dint", 7, ttl.seconds) {
                    client.getValue("$path/dint")?.asInt shouldBe 7
                }
                client.putValueWithKeepAlive("$path/dlong", 8L, ttl.seconds) {
                    client.getValue("$path/dlong")?.asLong shouldBe 8L
                }
                client.putValueWithKeepAlive("$path/dbytes", "raw".asByteSequence, ttl.seconds) {
                    client.getValue("$path/dbytes")?.asString shouldBe "raw"
                }

                client.deleteChildren(path)
            }
        }

        "putValuesWithKeepAlive keeps multiple keys live at once" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)

                val kvs = ["$path/m1" to "a".asByteSequence, "$path/m2" to "b".asByteSequence]
                client.putValuesWithKeepAlive(kvs, ttl) {
                    client.getValue("$path/m1")?.asString shouldBe "a"
                    client.getValue("$path/m2")?.asString shouldBe "b"
                }

                client.deleteChildren(path)
            }
        }
    }
}
