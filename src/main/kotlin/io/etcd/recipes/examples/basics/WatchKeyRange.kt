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

package io.etcd.recipes.examples.basics

import com.sudothought.common.util.sleep
import io.etcd.recipes.common.asPrefixWatchOption
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.count
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.getKeyValueChildren
import io.etcd.recipes.common.getKeys
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.watcher
import io.etcd.recipes.common.withKvClient
import io.etcd.recipes.common.withWatchClient
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/watchkeyrange"

    connectToEtcd(urls) { client ->
        client.withKvClient { kvClient ->
            kvClient.apply {

                client.withWatchClient { watchClient ->
                    watchClient.watcher(path, path.asPrefixWatchOption) { watchResponse ->
                        watchResponse.events
                            .forEach { watchEvent ->
                                println("${watchEvent.eventType} for ${watchEvent.keyValue.asString}")
                            }
                    }.use {
                        // Create empty root
                        putValue(path, "root")

                        println("After creation:")
                        println(getKeyValueChildren(path))
                        println(count(path))

                        sleep(5.seconds)

                        // Add children
                        putValue("$path/election/a", "a")
                        putValue("$path/election/b", "bb")
                        putValue("$path/waiting/c", "ccc")
                        putValue("$path/waiting/d", "dddd")

                        println("\nAfter putValues:")
                        println(getKeyValueChildren(path).asString)
                        println(count(path))

                        println("\nElection only:")
                        println(getKeyValueChildren("$path/election").asString)
                        println(count("$path/election"))

                        println("\nWaiting only:")
                        println(getKeyValueChildren("$path/waiting").asString)
                        println(count("$path/waiting"))

                        sleep(5.seconds)

                        // Delete root
                        delete(path)

                        // Delete children
                        getKeys(path).forEach {
                            println("Deleting key: $it")
                            delete(it)
                        }

                        println("\nAfter removal:")
                        println(getKeyValueChildren(path).asString)
                        println(count(path))

                        sleep(5.seconds)
                    }
                }
            }
        }
    }
}
