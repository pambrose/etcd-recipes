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

package io.etcd.recipes.examples.queue

import com.github.pambrose.common.concurrent.thread
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.queue.withDistributedQueue
import java.util.concurrent.CountDownLatch
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val queuePath = "/queue/example"
    val iterCount = 50
    val threadCount = 5

    connectToEtcd(urls) { client ->

        println("Count: ${client.getChildCount(queuePath)}")

        // Enqueue some data prior to dequeues
        withDistributedQueue(client, queuePath) {
            repeat(iterCount) { i -> enqueue("Before value $i") }
        }

        val latch = CountDownLatch(threadCount)
        repeat(threadCount) { sub ->
            thread(latch) {
                connectToEtcd(urls) { client ->
                    withDistributedQueue(client, queuePath) {
                        repeat((iterCount / threadCount) * 2) { println("Thread#: $sub Value: ${dequeue().asString}") }
                    }
                }
            }
        }

        sleep(2.seconds)

        // Now enqueue some data with dequeues waiting
        withDistributedQueue(client, queuePath) {
            repeat(iterCount) { i -> enqueue("After value $i") }
        }

        latch.await()

        println("Count: ${client.getChildCount(queuePath)}")
    }
}