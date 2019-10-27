/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.cache

import com.sudothought.common.util.randomId
import com.sudothought.common.util.sleep
import io.etcd.recipes.cache.PathChildrenCache.StartMode.POST_INITIALIZED_EVENT
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_ADDED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_REMOVED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_UPDATED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.INITIALIZED
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.withKvClient
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.seconds

class PathChildrenCacheTests {

    val urls = listOf("http://localhost:2379")

    fun generateTestData(count: Int): List<Pair<String, String>> {
        val names = List(count) { randomId(10) }
        val vals = List(count) { randomId(Random.nextInt(2, 25)) }
        return names.zip(vals)
    }

    fun compareData(count: Int, data: List<ChildData>, origData: List<Pair<String, String>>) {
        data.size shouldEqual count
        val currData = data.map { it.key to it.value.asString }.sortedBy { it.first }
        val updatedOrigData = origData.map { it.first to (it.second + "update") }.sortedBy { it.first }
        currData shouldEqual updatedOrigData
    }

    @Test
    fun listenerTestNoInitialData() {
        val count = 25
        val path = "/cache/listenerTestNoInitialData"
        val addCount = AtomicInteger(0)
        val updateCount = AtomicInteger(0)
        val deleteCount = AtomicInteger(0)
        val initCount = AtomicInteger(0)

        // Clear leftover data
        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvClient.deleteChildren(path)
            }
        }

        PathChildrenCache(urls, path)
            .use { cache ->
                cache.apply {

                    addListener { event: PathChildrenCacheEvent ->
                        //println("CB: ${event.type} ${event.childName} ${event.data?.asString}")
                        when (event.type) {
                            CHILD_ADDED   -> addCount.incrementAndGet()
                            CHILD_UPDATED -> updateCount.incrementAndGet()
                            CHILD_REMOVED -> deleteCount.incrementAndGet()
                            INITIALIZED   -> initCount.incrementAndGet()
                        }
                    }

                    start(true)
                    waitOnStartComplete()
                    currentData shouldEqual emptyList()

                    addCount.get() shouldEqual 0
                    updateCount.get() shouldEqual 0
                    deleteCount.get() shouldEqual 0
                    initCount.get() shouldEqual 0

                    val kvs = generateTestData(count)

                    connectToEtcd(urls) { client ->
                        client.withKvClient { kvClient ->
                            kvs.forEach { kv ->
                                kvClient.putValue("${path}/${kv.first}", kv.second)
                                kvClient.putValue("${path}/${kv.first}", kv.second + "update")
                            }
                        }
                    }

                    compareData(count, currentData, kvs)

                    connectToEtcd(urls) { client ->
                        client.withKvClient { kvClient ->
                            kvClient.deleteChildren(path)
                        }
                    }

                    currentData shouldEqual emptyList()
                }
            }

        sleep(1.seconds)

        addCount.get() shouldEqual count
        updateCount.get() shouldEqual count
        deleteCount.get() shouldEqual count
        initCount.get() shouldEqual 0
    }

    @Test
    fun listenerTestWithInitialData() {
        val count = 25
        val path = "/cache/listenerTestWithInitialData"
        val addCount = AtomicInteger(0)
        val updateCount = AtomicInteger(0)
        val deleteCount = AtomicInteger(0)
        val initCount = AtomicInteger(0)

        // Clear leftover data
        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvClient.deleteChildren(path)
            }
        }

        val kvs = generateTestData(count)

        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvs.forEach { kv ->
                    kvClient.putValue("${path}/${kv.first}", kv.second)
                    kvClient.putValue("${path}/${kv.first}", kv.second + "update")
                }
            }
        }

        PathChildrenCache(urls, path)
            .use { cache ->
                cache.apply {

                    addListener { event: PathChildrenCacheEvent ->
                        //println("CB: ${event.type} ${event.childName} ${event.data?.asString}")
                        when (event.type) {
                            CHILD_ADDED   -> addCount.incrementAndGet()
                            CHILD_UPDATED -> updateCount.incrementAndGet()
                            CHILD_REMOVED -> deleteCount.incrementAndGet()
                            INITIALIZED   -> initCount.incrementAndGet()
                        }
                    }

                    start(true)
                    waitOnStartComplete()
                    compareData(count, currentData, kvs)

                    addCount.get() shouldEqual 0
                    updateCount.get() shouldEqual 0
                    deleteCount.get() shouldEqual 0
                    initCount.get() shouldEqual 0

                    connectToEtcd(urls) { client ->
                        client.withKvClient { kvClient ->
                            kvClient.deleteChildren(path)
                        }
                    }

                    currentData shouldEqual emptyList()
                }
            }

        addCount.get() shouldEqual 0
        updateCount.get() shouldEqual 0
        deleteCount.get() shouldEqual count
        initCount.get() shouldEqual 0
    }

    @Test
    fun withInitialEventTest() {
        val count = 25
        val path = "/cache/noInitialDataTest"
        val addCount = AtomicInteger(0)
        val updateCount = AtomicInteger(0)
        val deleteCount = AtomicInteger(0)
        val initCount = AtomicInteger(0)

        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvClient.deleteChildren(path)
            }
        }

        val kvs = generateTestData(count)

        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvs.forEach { kv ->
                    kvClient.putValue("${path}/${kv.first}", kv.second)
                    kvClient.putValue("${path}/${kv.first}", kv.second + "update")
                }
            }
        }

        var initData: List<ChildData>? = null

        PathChildrenCache(urls, path)
            .use { cache ->
                cache.apply {

                    addListener { event: PathChildrenCacheEvent ->
                        //println("CB: ${event.type} ${event.childName} ${event.data?.asString}")
                        when (event.type) {
                            CHILD_ADDED   -> addCount.incrementAndGet()
                            CHILD_UPDATED -> updateCount.incrementAndGet()
                            CHILD_REMOVED -> deleteCount.incrementAndGet()
                            INITIALIZED   -> {
                                initCount.incrementAndGet()
                                initData = event.initialData
                            }
                        }
                    }

                    start(POST_INITIALIZED_EVENT)
                    waitOnStartComplete()
                    compareData(count, currentData, kvs)

                    connectToEtcd(urls) { client ->
                        client.withKvClient { kvClient ->
                            kvClient.deleteChildren(path)
                        }
                    }

                    currentData shouldEqual emptyList()
                }
            }

        compareData(count, initData!!, kvs)

        addCount.get() shouldEqual 0
        updateCount.get() shouldEqual 0
        deleteCount.get() shouldEqual count
        initCount.get() shouldEqual 1
    }
}