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
import com.sudothought.common.util.sleep
import io.etcd.recipes.common.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/foo"
    val keyval = "foobar"
    val latch = CountDownLatch(1)

    thread {
        latch.countDown {
            connectToEtcd(urls) { client ->
                client.withKvClient { kvClient ->
                    println("Assigning $path = $keyval")
                    kvClient.putValueWithKeepAlive(path, keyval, client) {
                        println("Starting sleep")
                        sleep(5.seconds)
                        println("Finished sleep")
                    }
                    println("Keep-alive is now terminated")
                    sleep(5.seconds)
                }
            }
            println("Releasing latch")
        }
    }

    val endWatchLatch = CountDownLatch(1)

    thread {
        connectToEtcd(urls) { client ->
            client.withWatchClient { watchClient ->
                watchClient.watcherWithLatch(path,
                                             endWatchLatch,
                                             { event -> println("Updated key ${event.keyAsString}") },
                                             { event -> println("Deleted key ${event.keyAsString}") })
            }
        }
    }

    latch.await()
    println("Releasing endWatchLatch")
    endWatchLatch.countDown()
}
