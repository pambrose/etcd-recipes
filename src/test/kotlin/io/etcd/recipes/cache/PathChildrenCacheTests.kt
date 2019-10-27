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
import kotlin.random.Random

class PathChildrenCacheTests {

    val urls = listOf("http://localhost:2379")

    @Test
    fun initialDataTest() {

    }

    @Test
    fun listenerTest() {

        val count = 25
        val cachePath = "/cache/listenerTest"
        var addCount = 0
        var updateCount = 0
        var deleteCount = 0
        var initCount = 0

        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvClient.deleteChildren(cachePath)
            }
        }

        val cache = PathChildrenCache(urls, cachePath)
        cache.apply {
            start(true)
            waitOnStartComplete()

            //currentData shouldEqual emptyList()

            addListener { event: PathChildrenCacheEvent ->
                //println("CB: ${event.type} ${event.childName} ${event.data?.asString}")
                when (event.type) {
                    CHILD_ADDED   -> addCount++
                    CHILD_UPDATED -> updateCount++
                    CHILD_REMOVED -> deleteCount++
                    INITIALIZED   -> initCount++
                }
            }

        }

        val names = List(count) { randomId(10) }
        val vals = List(count) { randomId(Random.nextInt(2, 25)) }
        val kvs = names.zip(vals)

        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvs.forEach { kv ->
                    kvClient.putValue("${cachePath}/${kv.first}", kv.second)
                    kvClient.putValue("${cachePath}/${kv.first}", kv.second + "update")
                }
            }
        }

        val data0 = cache.currentData
        data0.size shouldEqual count
        val currData = data0.map { it.key to it.value.asString }.sortedBy { it.first }
        val updatedOrigData = kvs.map { it.first to (it.second + "update") }.sortedBy { it.first }
        currData shouldEqual updatedOrigData

        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                kvClient.deleteChildren(cachePath)
            }
        }

        cache.currentData shouldEqual emptyList()

        addCount shouldEqual count
        updateCount shouldEqual count
        deleteCount shouldEqual count
        initCount shouldEqual 0
    }
}