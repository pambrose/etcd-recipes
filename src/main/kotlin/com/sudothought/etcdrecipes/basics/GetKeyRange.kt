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
import com.sudothought.etcdrecipes.jetcd.asByteSequence
import com.sudothought.etcdrecipes.jetcd.asPair
import com.sudothought.etcdrecipes.jetcd.asString
import com.sudothought.etcdrecipes.jetcd.count
import com.sudothought.etcdrecipes.jetcd.delete
import com.sudothought.etcdrecipes.jetcd.getChildrenKVs
import com.sudothought.etcdrecipes.jetcd.getChildrenKeys
import com.sudothought.etcdrecipes.jetcd.putValue
import com.sudothought.etcdrecipes.jetcd.watcher
import com.sudothought.etcdrecipes.jetcd.withKvClient
import com.sudothought.etcdrecipes.jetcd.withWatchClient
import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val keyname = "/keyrangetest"


    Client.builder().endpoints(url).build()
        .use { client ->
            client.withWatchClient { watchClient ->
                client.withKvClient { kvClient ->
                    kvClient.apply {

                        val option = WatchOption.newBuilder().withPrefix("/".asByteSequence).build()
                        watchClient.watcher(keyname, option) { watchResponse ->
                            watchResponse.events
                                .forEach { watchEvent ->
                                    println("${watchEvent.eventType} for ${watchEvent.keyValue.asPair.asString}")
                                }
                        }.use {
                            // Create empty root
                            putValue(keyname, "root")

                            println("After creation:")
                            println(getChildrenKVs(keyname).asString)
                            println(count(keyname))

                            sleep(5.seconds)

                            // Add children
                            putValue("$keyname/election/a", "a")
                            putValue("$keyname/election/b", "bb")
                            putValue("$keyname/waiting/c", "ccc")
                            putValue("$keyname/waiting/d", "dddd")

                            println("\nAfter addition:")
                            println(getChildrenKVs(keyname).asString)
                            println(count(keyname))

                            println("\nElections only:")
                            println(getChildrenKVs("$keyname/election").asString)
                            println(count("$keyname/election"))

                            println("\nWaitings only:")
                            println(getChildrenKVs("$keyname/waiting").asString)
                            println(count("$keyname/waiting"))

                            sleep(5.seconds)

                            // Delete root
                            delete(keyname)

                            // Delete children
                            getChildrenKeys(keyname).forEach {
                                println("Deleting key: $it")
                                delete(it)
                            }


                            println("\nAfter removal:")
                            println(getChildrenKVs(keyname).asString)
                            println(count(keyname))

                            sleep(5.seconds)

                        }
                    }
                }
            }
        }
}
