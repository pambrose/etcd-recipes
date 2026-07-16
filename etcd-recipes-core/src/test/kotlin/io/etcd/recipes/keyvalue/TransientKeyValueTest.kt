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

import com.pambrose.common.util.randomId
import com.pambrose.common.util.sleep
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class TransientKeyValueTest : StringSpec() {
    private val defaultValue = "nothing"

    init {
        "singleKVTest" {
            val path = "/keyvalue/singleKVTest"
            val id = randomId(8)

            connectToEtcd(urls) { client ->
                client.getValue(path, defaultValue) shouldBe defaultValue

                withTransientKeyValue(client, path, id) {
                    repeat(10) {
                        client.getValue(path)?.asString shouldBe id
                        sleep(1.seconds)
                    }
                }

                // Wait for node to go away
                sleep(5.seconds)
                client.getValue(path, defaultValue) shouldBe defaultValue
            }
        }

        "multiKVTest" {
            val count = 25
            val prefix = "/keyvalue"
            val paths = List(count) { "$prefix/multiKVTest$it" }
            val ids = List(count) { randomId(8) }

            connectToEtcd(urls) { client ->

                client.apply {
                    for (p in paths) {
                      getValue(p, defaultValue) shouldBe defaultValue
                    }

                    getChildCount(prefix) shouldBe 0

                    val kvs = List(count) { TransientKeyValue(client, paths[it], ids[it]) }

                    repeat(10) {
                        repeat(count) { j ->
                            getValue(paths[j])?.asString shouldBe ids[j]
                        }
                    }

                    getChildCount(prefix) shouldBe 25

                    for (kv in kvs) {
                      kv.close()
                    }

                    // Wait for nodes to go away
                    sleep(5.seconds)
                    for (p in paths) {
                      getValue(p, defaultValue) shouldBe defaultValue
                    }

                    getChildCount(prefix) shouldBe 0
                }
            }
        }
    }
}
