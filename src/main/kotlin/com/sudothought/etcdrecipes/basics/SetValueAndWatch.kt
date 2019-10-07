/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package com.sudothought.etcdrecipes.basics

import com.sudothought.common.util.repeatWithSleep
import com.sudothought.common.util.sleep
import com.sudothought.etcdrecipes.jetcd.asPair
import com.sudothought.etcdrecipes.jetcd.asString
import com.sudothought.etcdrecipes.jetcd.delete
import com.sudothought.etcdrecipes.jetcd.putValue
import com.sudothought.etcdrecipes.jetcd.watcher
import com.sudothought.etcdrecipes.jetcd.withKvClient
import com.sudothought.etcdrecipes.jetcd.withWatchClient
import io.etcd.jetcd.Client
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val countdown = CountDownLatch(2)
    val keyname = "/foo"
    val keyval = "foobar"

    thread {
        try {
            sleep(3.seconds)

            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        repeatWithSleep(10) { i, _ ->
                            val kv = keyval + i
                            println("Assigning $keyname = $kv")
                            kvClient.putValue(keyname, kv)

                            sleep(1.seconds)

                            println("Deleting $keyname")
                            kvClient.delete(keyname)
                        }
                    }
                }
        } finally {
            countdown.countDown()
        }
    }

    thread {
        try {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withWatchClient { watchClient ->
                        println("Starting watch")
                        watchClient.watcher(keyname) { watchResponse ->
                            watchResponse.events
                                .forEach { watchEvent ->
                                    println("Watch event: ${watchEvent.eventType} ${watchEvent.keyValue.asPair.asString}")
                                }
                        }.use {
                            sleep(5.seconds)
                            println("Closing watch")
                        }
                        println("Closed watch")
                    }
                }
        } finally {
            countdown.countDown()
        }
    }

    countdown.await()
}