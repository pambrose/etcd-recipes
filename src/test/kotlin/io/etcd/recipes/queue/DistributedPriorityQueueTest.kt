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

import com.sudothought.common.concurrent.thread
import com.sudothought.common.concurrent.withLock
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class DistributedPriorityQueueTest {
    val iterCount = 500
    val threadCount = 10
    val testData = List(iterCount) { "V %04d".format(it) }

    @Test
    fun serialTestNoWait() {
        val queuePath = "/queue/serialTestNoWait"
        val dequeuedData = mutableListOf<String>()

        connectToEtcd(urls) { client ->
            client.getChildCount(queuePath) shouldEqual 0
            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }
            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { dequeuedData += dequeue().asString } }
            client.getChildCount(queuePath) shouldEqual 0
        }

        if (iterCount <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun serialTestWithWait() {
        val queuePath = "/queue/serialTestWithWait"
        val dequeuedData = mutableListOf<String>()
        val latch = CountDownLatch(1)
        val semaphore = Semaphore(1)

        connectToEtcd(urls) { client ->
            client.getChildCount(queuePath) shouldEqual 0

            thread(latch) {
                connectToEtcd(urls) { client ->
                    withDistributedPriorityQueue(client, queuePath) {
                        repeat(iterCount) {
                            semaphore.withLock { dequeuedData += dequeue().asString }
                        }
                    }
                }
            }

            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }

            latch.await()

            client.getChildCount(queuePath) shouldEqual 0
        }

        if (iterCount <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun threadedTestNoWait() {
        val queuePath = "/queue/threadedTestNoWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = mutableListOf<String>()

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0

            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }

            repeat(threadCount) {
                thread(latch) {
                    connectToEtcd(urls) { client ->
                        withDistributedPriorityQueue(client, queuePath) {
                            repeat(iterCount / threadCount) { dequeuedData += dequeue().asString }
                        }
                    }
                }
            }

            latch.await()

            client.getChildCount(queuePath) shouldEqual 0
        }

        if (iterCount <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun threadedTestWithWait() {
        val queuePath = "/queue/threadedTestWithWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = synchronizedList(mutableListOf<String>())
        val semaphore = Semaphore(1)

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0

            repeat(threadCount) {
                thread(latch) {
                    connectToEtcd(urls) { client ->
                        withDistributedPriorityQueue(client, queuePath) {
                            repeat(iterCount / threadCount) { semaphore.withLock { dequeuedData += dequeue().asString } }
                        }
                    }
                }
            }

            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }

            latch.await()

            client.getChildCount(queuePath) shouldEqual 0
        }

        if (iterCount <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun pingPongTest() {
        val queuePath = "/queue/pingPongTest"
        val counter = AtomicInteger(0)
        val token = "Pong"
        val latch = CountDownLatch(threadCount)
        val iterCount = 100

        // Prime the queue with a value
        connectToEtcd(urls) { client ->
            withDistributedPriorityQueue(client, queuePath) { enqueue(token, 1u) }
        }

        repeat(threadCount) {
            thread(latch) {
                connectToEtcd(urls) { client ->
                    withDistributedPriorityQueue(client, queuePath) {
                        repeat(iterCount) {
                            val v = dequeue().asString
                            v shouldEqual token
                            enqueue(v, 1u)
                            counter.incrementAndGet()
                        }
                    }
                }
            }
        }

        latch.await()

        connectToEtcd(urls) { client ->
            withDistributedPriorityQueue(client, queuePath) {
                val v = dequeue().asString
                v shouldEqual token
            }
        }

        counter.get() shouldEqual threadCount * iterCount
    }

    @Test
    fun serialTestNoWaitWithPriorities() {
        val queuePath = "/queue/serialTestNoWaitWithPriorities"
        val dequeuedData = mutableListOf<String>()

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0
            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], i) } }
            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { dequeuedData += dequeue().asString } }
            client.getChildCount(queuePath) shouldEqual 0
        }

        if (iterCount <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun serialTestNoWaitWithReversedPriorities() {
        val queuePath = "/queue/serialTestNoWaitWithReversedPriorities"
        val dequeuedData = mutableListOf<String>()

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0
            withDistributedPriorityQueue(client, queuePath) {
                repeat(iterCount) { i -> enqueue(testData[i], (iterCount - i)) }
            }
            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { dequeuedData += dequeue().asString } }
            client.getChildCount(queuePath) shouldEqual 0
        }

        if (iterCount <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[iterCount - i - 1] }
        dequeuedData shouldEqual testData.reversed()
    }

    companion object : KLogging()
}