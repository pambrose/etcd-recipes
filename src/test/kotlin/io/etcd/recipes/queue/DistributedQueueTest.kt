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
import com.sudothought.common.util.sleep
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
import kotlin.time.seconds

class DistributedQueueTest {
    val iterCount = 500
    val threadCount = 10
    val testData = List(iterCount) { "V $it" }

    @Test
    fun serialTestNoWait() {
        val queuePath = "/queue/serialTestNoWait"
        val dequeuedData = mutableListOf<String>()

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0
            withDistributedQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i]) } }
            withDistributedQueue(client, queuePath) { repeat(iterCount) { dequeuedData += dequeue().asString } }
            client.getChildCount(queuePath) shouldEqual 0
        }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun serialTestWithWait() {
        val queuePath = "/queue/serialTestWithWait"
        val dequeuedData = synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        val semaphore = Semaphore(1)

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0

            thread(latch) {
                connectToEtcd(urls) { client ->
                    withDistributedQueue(client, queuePath) {
                        repeat(iterCount) { semaphore.withLock { dequeuedData += dequeue().asString } }
                    }
                }
            }

            sleep(2.seconds)

            withDistributedQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i]) } }

            latch.await()

            client.getChildCount(queuePath) shouldEqual 0
        }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun threadedTestNoWait1() {
        repeat(5) {
            threadedTestNoWait(10, 1)
            threadedTestNoWait(10, 2)
            threadedTestNoWait(10, 5)
            threadedTestNoWait(10, 10)
        }
    }

    @Test
    fun threadedTestNoWait2() {
        threadedTestNoWait(100, 1)
        threadedTestNoWait(100, 2)
        threadedTestNoWait(100, 5)
        threadedTestNoWait(100, 10)
    }

    @Test
    fun threadedTestNoWait3() {
        threadedTestNoWait(iterCount, threadCount)
    }

    fun threadedTestNoWait(iterCount: Int, threadCount: Int) {
        val queuePath = "/queue/threadedTestNoWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = synchronizedList(mutableListOf<String>())
        val semaphore = Semaphore(1)
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0

            withDistributedQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i]) } }

            repeat(threadCount) {
                thread(latch) {
                    connectToEtcd(urls) { client ->
                        withDistributedQueue(client, queuePath) {
                            repeat(iterCount / threadCount) { semaphore.withLock { dequeuedData += dequeue().asString } }
                        }
                    }
                }
            }

            latch.await()

            client.getChildCount(queuePath) shouldEqual 0
        }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun threadedTestWithWait1() {
        repeat(5) {
            threadedTestWithWait(10, 1)
            threadedTestWithWait(10, 2)
            threadedTestWithWait(10, 5)
            threadedTestWithWait(10, 10)
        }
    }

    @Test
    fun threadedTestWithWait2() {
        threadedTestWithWait(100, 1)
        threadedTestWithWait(100, 2)
        threadedTestWithWait(100, 5)
        threadedTestWithWait(100, 10)
    }

    @Test
    fun threadedTestWithWait3() {
        threadedTestWithWait(iterCount, threadCount)
    }

    fun threadedTestWithWait(iterCount: Int, threadCount: Int) {
        val queuePath = "/queue/threadedTestWithWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = synchronizedList(mutableListOf<String>())
        val semaphore = Semaphore(1)
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldEqual 0

            repeat(threadCount) {
                thread(latch) {
                    withDistributedQueue(client, queuePath) {
                        repeat(iterCount / threadCount) {
                            semaphore.withLock { dequeuedData += dequeue().asString }
                        }
                    }
                }
            }

            sleep(2.seconds)

            withDistributedQueue(client, queuePath) {
                repeat(iterCount) { i ->
                    val v = testData[i]
                    enqueue(v)
                    //println("Enqueued: $v")
                }
            }

            latch.await()

            client.getChildCount(queuePath) shouldEqual 0
        }

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
            withDistributedQueue(client, queuePath) { enqueue(token) }
        }

        repeat(threadCount) {
            thread(latch) {
                connectToEtcd(urls) { client ->
                    withDistributedQueue(client, queuePath) {
                        repeat(iterCount) {
                            val v = dequeue().asString
                            v shouldEqual token
                            enqueue(v)
                            counter.incrementAndGet()
                        }
                    }
                }
            }
        }

        latch.await()

        connectToEtcd(urls) { client ->
            withDistributedQueue(client, queuePath) {
                val v = dequeue().asString
                v shouldEqual token
            }
        }

        counter.get() shouldEqual threadCount * iterCount
    }

    companion object : KLogging()
}