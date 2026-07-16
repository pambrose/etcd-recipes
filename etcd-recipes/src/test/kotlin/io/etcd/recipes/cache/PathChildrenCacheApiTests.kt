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

package io.etcd.recipes.cache

import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Covers the snapshot-accessor side of [PathChildrenCache] — currentData,
 * currentDataAsMap, getCurrentData, rebuild, and clearListeners — which the
 * existing event-driven tests do not exercise.
 */
class PathChildrenCacheApiTests : StringSpec() {
    private val path = "/caches/${javaClass.simpleName}"

    init {
        "currentData, currentDataAsMap and getCurrentData reflect seeded children" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)
                client.putValue("$path/k1", "v1")
                client.putValue("$path/k2", "v2")

                PathChildrenCache(client, path).use { cache ->
                    cache.start(buildInitial = true)

                    cache.currentDataAsMap.keys shouldContainExactlyInAnyOrder listOf("k1", "k2")
                    cache.getCurrentData("k1")?.asString shouldBe "v1"
                    cache.getCurrentData("missing") shouldBe null
                    cache.currentData.map { it.key to it.value.asString } shouldContainExactlyInAnyOrder
                        listOf("k1" to "v1", "k2" to "v2")
                }

                client.deleteChildren(path)
            }
        }

        "rebuild re-reads children added directly to etcd" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)
                client.putValue("$path/a", "1")

                PathChildrenCache(client, path).use { cache ->
                    cache.start(buildInitial = true)
                    cache.currentDataAsMap.keys shouldContainExactlyInAnyOrder listOf("a")

                    client.putValue("$path/b", "2")
                    cache.rebuild()
                    cache.currentDataAsMap.keys shouldContainExactlyInAnyOrder listOf("a", "b")
                }

                client.deleteChildren(path)
            }
        }

        "clearListeners removes registered listeners before events fire" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(path)

                PathChildrenCache(client, path).use { cache ->
                    val events = AtomicInt(0)
                    cache.addListener { events.incrementAndFetch() }
                    cache.clearListeners()
                    cache.start(buildInitial = true)

                    client.putValue("$path/x", "v")
                    // Give any incorrectly-retained listener a chance to fire.
                    Thread.sleep(1000)
                    events.load() shouldBe 0
                }

                client.deleteChildren(path)
            }
        }
    }
}
