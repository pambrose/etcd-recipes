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

package io.etcd.recipes.examples.cache

import com.sudothought.common.util.sleep
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.cache.PathChildrenCacheEvent
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.putValue
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val cachePath = "/cache/test"

    etcdExec(urls) { _, kvClient ->
        kvClient.putValue("${cachePath}/child1", "a child 1 value")
        kvClient.putValue("${cachePath}/child2", "a child 2 value")
    }

    sleep(1.seconds)

    PathChildrenCache(urls, cachePath)
        .use { cache ->
            cache.apply {
                addListener { event: PathChildrenCacheEvent ->
                    println("CB: ${event.type} ${event.childName} ${event.data?.asString}")
                }

                start(true)
                waitOnStartComplete()
                currentData.forEach { println("${it.key} ${it.value.asString}") }

                etcdExec(urls) { _, kvClient -> println("Deleted: ${kvClient.deleteChildren(cachePath)}") }

                sleep(1.seconds)
            }
        }
}