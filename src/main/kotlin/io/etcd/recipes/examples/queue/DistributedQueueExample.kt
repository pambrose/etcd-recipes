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

package io.etcd.recipes.examples.queue

import com.sudothought.common.util.sleep
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.getChildrenCount
import io.etcd.recipes.queue.DistributedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val queuePath = "/queue/example"
    val count = 50
    val subcount = 5

    etcdExec(urls) { _, kvClient ->
        println("Count: ${kvClient.getChildrenCount(queuePath)}")
    }

    // Enqueue some data prior to dequeues
    DistributedQueue(urls, queuePath).use { queue ->
        repeat(count) { i -> queue.enqueue("Value $i") }
    }

    val latch = CountDownLatch(subcount)
    repeat(subcount) { sub ->
        thread {
            DistributedQueue(urls, queuePath)
                .use { queue ->
                    repeat((count / subcount) * 2) { i -> println("$sub ${queue.dequeue().asString}") }
                }
            latch.countDown()
        }
    }

    sleep(2.seconds)

    // Now enqueue some data with dequeues waiting
    DistributedQueue(urls, queuePath)
        .use { queue ->
            repeat(count) { i -> queue.enqueue("Value $i") }
        }

    latch.await()

    etcdExec(urls) { _, kvClient ->
        println("Count: ${kvClient.getChildrenCount(queuePath)}")
    }
}