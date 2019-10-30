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

import com.sudothought.common.concurrent.countDown
import com.sudothought.common.util.repeatWithSleep
import com.sudothought.common.util.sleep
import io.etcd.jetcd.watch.WatchResponse
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.watcher
import io.etcd.recipes.common.withKvClient
import io.etcd.recipes.common.withWatchClient
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/foo"
    val keyval = "foobar"
    val latch = CountDownLatch(2)

    thread {
        latch.countDown {
            sleep(3.seconds)

            connectToEtcd(urls) { client ->
                client.withKvClient { kvClient ->
                    repeatWithSleep(10) { i, _ ->
                        val kv = keyval + i
                        println("Assigning $path = $kv")
                        kvClient.putValue(path, kv)
                        sleep(2.seconds)
                        println("Deleting $path")
                        kvClient.delete(path)
                    }
                }
            }
        }
    }

    thread {
        latch.countDown {
            connectToEtcd(urls) { client ->
                client.withWatchClient { watchClient ->
                    watchClient.watcher(path) { watchResponse: WatchResponse ->
                        watchResponse.events
                            .forEach { watchEvent ->
                                println("Watch event: ${watchEvent.eventType} ${watchEvent.keyValue.asString}")
                            }
                    }.use {
                        println("Started watch")
                        sleep(10.seconds)
                        println("Closing watch")
                    }
                    println("Closed watch")
                }
            }
        }
    }

    latch.await()
}