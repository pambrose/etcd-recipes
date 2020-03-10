/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.examples.basics

import com.github.pambrose.common.concurrent.thread
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.keyAsString
import io.etcd.recipes.common.putValueWithKeepAlive
import io.etcd.recipes.common.watcherWithLatch
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/foo"
    val keyval = "foobar"
    val latch = CountDownLatch(1)
    val endWatchLatch = CountDownLatch(1)

    thread {
        connectToEtcd(urls) { client ->
            client.watcherWithLatch(path,
                                    endWatchLatch,
                                    { event -> println("Updated key: ${event.keyAsString}") },
                                    { event -> println("Deleted key: ${event.keyAsString}") })
        }
    }

    thread(latch) {
        connectToEtcd(urls) { client ->
            println("Assigning $path = $keyval")
            client.putValueWithKeepAlive(path, keyval, 2.seconds) {
                println("Starting sleep")
                sleep(5.seconds)
                println("Finished sleep")
            }
            println("Keep-alive is now terminated")
            sleep(5.seconds)
        }
        println("Releasing latch")
    }

    latch.await()

    println("Done")
    endWatchLatch.countDown()
}