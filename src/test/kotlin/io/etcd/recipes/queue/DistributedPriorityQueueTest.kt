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
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.getChildrenCount
import io.etcd.recipes.common.urls
import io.etcd.recipes.common.withDistributedPriorityQueue
import mu.KLogging
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class DistributedPriorityQueueTest {
    val count = 500
    val subcount = 10
    val testData = List(count) { "V %04d".format(it) }

    @Test
    fun serialTestNoWait() {
        val queuePath = "/queue/serialTestNoWait"
        val dequeuedData = mutableListOf<String>()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i], 1u) } }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { dequeuedData += dequeue().asString } }

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        if (count <= 500)
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

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        thread(latch) {
            withDistributedPriorityQueue(urls, queuePath) {
                repeat(count) {
                    semaphore.withLock { dequeuedData += dequeue().asString }
                }
            }
        }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i], 1u) } }

        latch.await()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        if (count <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun threadedTestNoWait() {
        val queuePath = "/queue/threadedTestNoWait"
        val latch = CountDownLatch(subcount)
        val dequeuedData = mutableListOf<String>()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i], 1u) } }

        repeat(subcount) {
            thread(latch) {
                withDistributedPriorityQueue(urls, queuePath) {
                    repeat(count / subcount) { dequeuedData += dequeue().asString }
                }
            }
        }

        latch.await()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        if (count <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun threadedTestWithWait() {
        val queuePath = "/queue/threadedTestWithWait"
        val latch = CountDownLatch(subcount)
        val dequeuedData = mutableListOf<String>()
        val semaphore = Semaphore(1)

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        repeat(subcount) {
            thread(latch) {
                withDistributedPriorityQueue(urls, queuePath) {
                    repeat(count / subcount) { semaphore.withLock { dequeuedData += dequeue().asString } }
                }
            }
        }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i], 1u) } }

        latch.await()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        if (count <= 500)
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
        val latch = CountDownLatch(subcount)

        // Prime the queue with a value
        withDistributedPriorityQueue(urls, queuePath) { enqueue(token, 1u) }

        repeat(subcount) {
            thread(latch) {
                withDistributedPriorityQueue(urls, queuePath) {
                    repeat(count) {
                        val v = dequeue().asString
                        v shouldEqual token
                        enqueue(v, 1u)
                        counter.incrementAndGet()
                    }
                }
            }
        }

        latch.await()
        withDistributedPriorityQueue(urls, queuePath) {
            val v = dequeue().asString
            v shouldEqual token
        }

        counter.get() shouldEqual subcount * count
    }

    @Test
    fun serialTestNoWaitWithPriorities() {
        val queuePath = "/queue/serialTestNoWaitWithPriorities"
        val dequeuedData = mutableListOf<String>()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i], i) } }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { dequeuedData += dequeue().asString } }

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        if (count <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[i] }
        dequeuedData shouldEqual testData
    }

    @Test
    fun serialTestNoWaitWithReversedPriorities() {
        val queuePath = "/queue/serialTestNoWaitWithReversedPriorities"
        val dequeuedData = mutableListOf<String>()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i], (count - i)) } }

        withDistributedPriorityQueue(urls, queuePath) { repeat(count) { dequeuedData += dequeue().asString } }

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        if (count <= 500)
            logger.info { dequeuedData }

        dequeuedData.size shouldEqual testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldEqual testData[count - i - 1] }
        dequeuedData shouldEqual testData.reversed()
    }

    companion object : KLogging()
}
