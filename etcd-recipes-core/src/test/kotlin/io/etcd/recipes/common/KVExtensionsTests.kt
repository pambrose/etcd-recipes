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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KVExtensionsTests : StringSpec() {
    private val path = "/kv-extensions/${javaClass.simpleName}"

    init {
        "putValue overloads store typed values that read back" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)

                client.putValue("$path/str", "hello")
                client.putValue("$path/int", 123)
                client.putValue("$path/long", 456L)
                client.putValue("$path/bytes", "raw".asByteSequence)

                client.getValue("$path/str", "def") shouldBe "hello"
                client.getValue("$path/int", -1) shouldBe 123
                client.getValue("$path/long", -1L) shouldBe 456L
                client.getValue("$path/bytes")?.asString shouldBe "raw"

                client.deleteChildren(path)
            }
        }

        "getValue returns the default when the key is absent" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)

                client.getValue("$path/missing") shouldBe null
                client.getValue("$path/missing", "fallback") shouldBe "fallback"
                client.getValue("$path/missing", 7) shouldBe 7
                client.getValue("$path/missing", 7L) shouldBe 7L
            }
        }

        "isKeyPresent / isKeyNotPresent reflect existence" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)
                val key = "$path/present"

                client.isKeyPresent(key) shouldBe false
                client.isKeyNotPresent(key) shouldBe true

                client.putValue(key, "v")
                client.isKeyPresent(key) shouldBe true
                client.isKeyNotPresent(key) shouldBe false

                client.deleteChildren(path)
            }
        }

        "deleteKey and deleteKeys remove keys" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)
                client.putValue("$path/d1", "v")
                client.putValue("$path/d2", "v")
                client.putValue("$path/d3", "v")

                client.deleteKey("$path/d1")
                client.isKeyPresent("$path/d1") shouldBe false

                client.deleteKeys("$path/d2", "$path/d3")
                client.isKeyPresent("$path/d2") shouldBe false
                client.isKeyPresent("$path/d3") shouldBe false
            }
        }

        "getKeyValuePairs returns all children under a prefix" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)
                val prefix = "$path/pairs/"
                client.putValue("${prefix}a", "1")
                client.putValue("${prefix}b", "2")

                val getOption = getOption { isPrefix(true) }
                val pairs = client.getKeyValuePairs(prefix, getOption).asString

                pairs shouldContainExactlyInAnyOrder listOf("${prefix}a" to "1", "${prefix}b" to "2")

                client.deleteChildren(path)
            }
        }

        "KeyValue converts to typed pairs" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)
                client.putValue("$path/kv-str", "text")
                client.putValue("$path/kv-int", 11)
                client.putValue("$path/kv-long", 22L)

                client.getResponse("$path/kv-str").kvs.first().asString shouldBe ("$path/kv-str" to "text")
                client.getResponse("$path/kv-int").kvs.first().asInt shouldBe ("$path/kv-int" to 11)
                client.getResponse("$path/kv-long").kvs.first().asLong shouldBe ("$path/kv-long" to 22L)

                client.deleteChildren(path)
            }
        }

        "getResponse for an absent key returns no kvs" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)
                val resp = client.getResponse("$path/none")
                resp.kvs.isEmpty() shouldBe true
                resp shouldNotBe null
            }
        }
    }
}
