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
import com.github.pambrose.common.util.repeatWithSleep
import com.github.pambrose.common.util.sleep
import io.etcd.jetcd.watch.WatchResponse
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.withWatcher
import java.util.concurrent.CountDownLatch
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/foo"
    val keyval = "foobar"
    val latch = CountDownLatch(2)

    thread(latch) {
        sleep(3.seconds)
        connectToEtcd(urls) { client ->
            repeatWithSleep(10) { i, _ ->
                val kv = keyval + i
                println("Assigning $path = $kv")
                client.putValue(path, kv)
                sleep(2.seconds)
                println("Deleting $path")
                client.deleteKey(path)
            }
        }
    }

    thread(latch) {
        connectToEtcd(urls) { client ->
            client.withWatcher(path,
                               block = { watchResponse: WatchResponse ->
                                   for (event in watchResponse.events)
                                       println("Watch event: ${event.eventType} ${event.keyValue.asString}")
                               }) {
                println("Started watch")
                sleep(10.seconds)
                println("Closing watch")
            }
            println("Closed watch")
        }
    }

    latch.await()
}