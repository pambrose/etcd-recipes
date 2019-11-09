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

package io.etcd.recipes.queue

import io.etcd.recipes.common.asString
import io.etcd.recipes.common.urls
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DistributedQueueTest {
    /*
        @Test
        fun serialTestNoWait() {
            val queuePath = "/queue/serialTestNoWait"
            val count = 2000
            val testData = List(count) { "Value $it" }
            val dequeuedData = mutableListOf<String>()

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            DistributedQueue(urls, queuePath)
                .use { queue ->
                    repeat(count) { i -> queue.enqueue(testData[i]) }
                }

            DistributedQueue(urls, queuePath)
                .use { queue ->
                    repeat(count) {
                        dequeuedData += queue.dequeue().asString
                    }
                }

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            if (count <= 500)
                println(dequeuedData)

            dequeuedData.size shouldEqual testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
            dequeuedData shouldEqual testData
        }

        @Test
        fun serialTestWithWait() {
            val queuePath = "/queue/serialTestWithWait"
            val count = 2000
            val testData = List(count) { "Value $it" }
            val dequeuedData = mutableListOf<String>()
            val latch = CountDownLatch(1)
            val semaphore = Semaphore(1)

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            thread {
                DistributedQueue(urls, queuePath)
                    .use { queue ->
                        repeat(count) {
                            semaphore.withLock {
                                dequeuedData += queue.dequeue().asString
                            }
                        }
                    }
                latch.countDown()
            }

            DistributedQueue(urls, queuePath)
                .use { queue ->
                    repeat(count) { i -> queue.enqueue(testData[i]) }
                }

            latch.await()

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            if (count <= 500)
                println(dequeuedData)

            dequeuedData.size shouldEqual testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
            dequeuedData shouldEqual testData
        }

        @Test
        fun threadedTestNoWait() {
            val queuePath = "/queue/threadedTestNoWait"
            val count = 2000
            val subcount = 10
            val latch = CountDownLatch(subcount)
            val testData = List(count) { "Value $it" }
            val dequeuedData = mutableListOf<String>()

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            DistributedQueue(urls, queuePath)
                .use { queue ->
                    repeat(count) { i -> queue.enqueue(testData[i]) }
                }

            repeat(subcount) {
                thread {
                    DistributedQueue(urls, queuePath)
                        .use { queue ->
                            repeat(count / subcount) {
                                dequeuedData += queue.dequeue().asString
                            }
                        }
                    latch.countDown()
                }
            }

            latch.await()

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            if (count <= 500)
                println(dequeuedData)

            dequeuedData.size shouldEqual testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
            dequeuedData shouldEqual testData
        }

        @Test
        fun threadedTestWithWait() {
            val queuePath = "/queue/threadedTestWithWait"
            val count = 2000
            val subcount = 10
            val latch = CountDownLatch(subcount)
            val testData = List(count) { "Value $it" }
            val dequeuedData = mutableListOf<String>()
            val semaphore = Semaphore(1)

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            repeat(subcount) {
                thread {
                    DistributedQueue(urls, queuePath)
                        .use { queue ->
                            repeat(count / subcount) {
                                semaphore.withLock {
                                    dequeuedData += queue.dequeue().asString
                                }
                            }
                        }
                    latch.countDown()
                }
            }

            DistributedQueue(urls, queuePath)
                .use { queue ->
                    repeat(count) { i -> queue.enqueue(testData[i]) }
                }

            latch.await()

            etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

            if (count <= 500)
                println(dequeuedData)

            dequeuedData.size shouldEqual testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
            dequeuedData shouldEqual testData
        }
    */
    @Test
    fun pingPongTest() {
        val queuePath = "/queue/pingPongTest"
        val count = 200
        val subcount = 10
        val counter = AtomicInteger(0)
        val token = "Pong"
        val latch = CountDownLatch(subcount)

        DistributedQueue(urls, queuePath).use { queue -> queue.enqueue(token) }

        repeat(subcount) { threadCnt ->
            thread {
                DistributedQueue(urls, queuePath)
                    .use { queue ->
                        repeat(count) { i ->
                            val v = queue.dequeue().asString
                            v shouldEqual token
                            queue.enqueue(v)
                            //println("$threadCnt $i $v")
                            counter.incrementAndGet()
                        }
                    }
                latch.countDown()
            }
        }

        latch.await()
        DistributedQueue(urls, queuePath).use { queue ->
            val v = queue.dequeue().asString
            v shouldEqual token
        }

        counter.get() shouldEqual subcount * count
    }
}