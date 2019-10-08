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

package com.sudothought.etcdrecipes.basics

import com.sudothought.common.util.sleep
import com.sudothought.etcdrecipes.jetcd.*
import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/keyrangetest"

    Client.builder().endpoints(*urls.toTypedArray()).build()
        .use { client ->
            client.withWatchClient { watchClient ->
                client.withKvClient { kvClient ->
                    kvClient.apply {

                        val option = WatchOption.newBuilder().withPrefix("/".asByteSequence).build()
                        watchClient.watcher(path, option) { watchResponse ->
                            watchResponse.events
                                .forEach { watchEvent ->
                                    println("${watchEvent.eventType} for ${watchEvent.keyValue.asPair.asString}")
                                }
                        }.use {
                            // Create empty root
                            putValue(path, "root")

                            println("After creation:")
                            println(getKeyValues(path).valuesAsString)
                            println(count(path))

                            sleep(5.seconds)

                            // Add children
                            putValue("$path/election/a", "a")
                            putValue("$path/election/b", "bb")
                            putValue("$path/waiting/c", "ccc")
                            putValue("$path/waiting/d", "dddd")

                            println("\nAfter addition:")
                            println(getKeyValues(path).valuesAsString)
                            println(count(path))

                            println("\nElections only:")
                            println(getKeyValues("$path/election").valuesAsString)
                            println(count("$path/election"))

                            println("\nWaitings only:")
                            println(getKeyValues("$path/waiting").valuesAsString)
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
                            println(getKeyValues(path).valuesAsString)
                            println(count(path))

                            sleep(5.seconds)
                        }
                    }
                }
            }
        }
}
