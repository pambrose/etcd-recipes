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

import com.pambrose.common.util.randomId
import com.pambrose.common.util.sleep
import io.etcd.recipes.cache.PathChildrenCache.StartMode.POST_INITIALIZED_EVENT
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_ADDED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_REMOVED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_UPDATED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.INITIALIZED
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.putValuesWithKeepAlive
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class PathChildrenCacheTests : StringSpec() {
    private val suffix = "update"

    private fun generateTestData(count: Int): List<Pair<String, String>> {
        val names = List(count) { "Key:%05d".format(it) }
        val vals = List(count) { randomId(Random.nextInt(2, 10)) }
        return names.zip(vals)
    }

    private fun compareData(
        count: Int,
        data: List<ChildData>,
        origData: List<Pair<String, String>>,
        suffix: String = "",
    ) {
        data.size shouldBe count
        val currData = data.map { it.key to it.value.asString }.sortedBy { it.first }
        val updatedOrigData = origData.map { it.first to (it.second + suffix) }.sortedBy { it.first }
        currData.forEachIndexed { i, pair ->
            pair shouldBe updatedOrigData[i]
        }

        currData shouldBe updatedOrigData
    }

    init {
        "listenerTestNoInitialData" {
            val count = 25
            val path = "/cache/listenerTestNoInitialData"
            val addCount = AtomicInteger(0)
            val updateCount = AtomicInteger(0)
            val deleteCount = AtomicInteger(0)
            val initCount = AtomicInteger(0)

            connectToEtcd(urls) { client ->

                // Clear leftover data
                client.deleteChildren(path)
                client.getChildCount(path) shouldBe 0

                withPathChildrenCache(client, path) {
                    addListener { event: PathChildrenCacheEvent ->
                        when (event.type) {
                            CHILD_ADDED -> addCount.incrementAndGet()
                            CHILD_UPDATED -> updateCount.incrementAndGet()
                            CHILD_REMOVED -> deleteCount.incrementAndGet()
                            INITIALIZED -> initCount.incrementAndGet()
                        }
                    }

                    start(true)
                    waitOnStartComplete()
                    currentData shouldBe emptyList()

                    addCount.get() shouldBe 0
                    updateCount.get() shouldBe 0
                    deleteCount.get() shouldBe 0
                    initCount.get() shouldBe 0

                    val kvs = generateTestData(count)

                    kvs.forEach { kv ->
                        client.putValue("$path/${kv.first}", kv.second)
                        client.putValue("$path/${kv.first}", kv.second + suffix)
                    }

                    sleep(5.seconds)
                    compareData(count, currentData, kvs, suffix)

                    client.deleteChildren(path)

                    sleep(5.seconds)
                    currentData shouldBe emptyList()
                }
            }

            sleep(5.seconds)

            addCount.get() shouldBe count
            updateCount.get() shouldBe count
            deleteCount.get() shouldBe count
            initCount.get() shouldBe 0
        }

        "listenerTestWithInitialData" {
            val count = 25
            val path = "/cache/listenerTestWithInitialData"
            val addCount = AtomicInteger(0)
            val updateCount = AtomicInteger(0)
            val deleteCount = AtomicInteger(0)
            val initCount = AtomicInteger(0)

            connectToEtcd(urls) { client ->

                // Clear leftover data
                client.deleteChildren(path)
                client.getChildCount(path) shouldBe 0

                val testKvs = generateTestData(count)

                testKvs.forEach { kv ->
                    client.putValue("$path/${kv.first}", kv.second)
                    client.putValue("$path/${kv.first}", kv.second + suffix)
                }

                withPathChildrenCache(client, path) {
                    addListener { event: PathChildrenCacheEvent ->
                        when (event.type) {
                            CHILD_ADDED -> addCount.incrementAndGet()
                            CHILD_UPDATED -> updateCount.incrementAndGet()
                            CHILD_REMOVED -> deleteCount.incrementAndGet()
                            INITIALIZED -> initCount.incrementAndGet()
                        }
                    }

                    start(true)
                    waitOnStartComplete()

                    sleep(5.seconds)
                    compareData(count, currentData, testKvs, suffix)

                    addCount.get() shouldBe 0
                    updateCount.get() shouldBe 0
                    deleteCount.get() shouldBe 0
                    initCount.get() shouldBe 0

                    client.deleteChildren(path)

                    sleep(5.seconds)
                    currentData shouldBe emptyList()
                }
            }

            addCount.get() shouldBe 0
            updateCount.get() shouldBe 0
            deleteCount.get() shouldBe count
            initCount.get() shouldBe 0
        }

        "withInitialEventTest" {
            val count = 25
            val path = "/cache/noInitialDataTest"
            val addCount = AtomicInteger(0)
            val updateCount = AtomicInteger(0)
            val deleteCount = AtomicInteger(0)
            val initCount = AtomicInteger(0)

            connectToEtcd(urls) { client ->

                // Clear leftover data
                client.deleteChildren(path)
                client.getChildCount(path) shouldBe 0

                val kvs = generateTestData(count)

                kvs.forEach { kv ->
                    client.putValue("$path/${kv.first}", kv.second)
                    client.putValue("$path/${kv.first}", kv.second + suffix)
                }

                var initData: List<ChildData>? = null

                withPathChildrenCache(client, path) {
                    addListener { event: PathChildrenCacheEvent ->
                        when (event.type) {
                            CHILD_ADDED -> addCount.incrementAndGet()
                            CHILD_UPDATED -> updateCount.incrementAndGet()
                            CHILD_REMOVED -> deleteCount.incrementAndGet()
                            INITIALIZED -> {
                                initCount.incrementAndGet()
                                initData = event.initialData
                            }
                        }
                    }

                    start(POST_INITIALIZED_EVENT)
                    waitOnStartComplete()

                    sleep(5.seconds)
                    compareData(count, currentData, kvs, suffix)

                    client.deleteChildren(path)

                    sleep(5.seconds)
                    currentData shouldBe emptyList()
                }

                compareData(count, initData!!, kvs, suffix)
            }

            addCount.get() shouldBe 0
            updateCount.get() shouldBe 0
            deleteCount.get() shouldBe count
            initCount.get() shouldBe 1
        }

        "leasedValuesTest" {
            val count = 25
            val path = "/cache/leasedValuesTest"
            val kvs = generateTestData(count)

            connectToEtcd(urls) { client ->

                withPathChildrenCache(client, path) {
                    start(false)

                    val bsvals = kvs.map { "$path/${it.first}" to it.second.asByteSequence }
                    client.putValuesWithKeepAlive(bsvals, 2.seconds) {
                        sleep(5.seconds)
                        val data = currentData

                        compareData(count, data, kvs)
                    }

                    sleep(5.seconds)
                    currentData shouldBe emptyList()
                }
            }
        }
    }
}
