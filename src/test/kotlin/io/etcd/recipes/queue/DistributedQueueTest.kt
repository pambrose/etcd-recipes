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
import io.etcd.recipes.common.withDistributedQueue
import mu.KLogging
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class DistributedQueueTest {
    val count = 500
    val subcount = 10
    val testData = List(count) { "V $it" }

    @Test
    fun serialTestNoWait() {
        val queuePath = "/queue/serialTestNoWait"
        val dequeuedData = mutableListOf<String>()

        etcdExec(urls) { _, kvClient -> kvClient.getChildrenCount(queuePath) shouldEqual 0 }

        withDistributedQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i]) } }

        withDistributedQueue(urls, queuePath) { repeat(count) { dequeuedData += dequeue().asString } }

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
            withDistributedQueue(urls, queuePath) {
                repeat(count) {
                    semaphore.withLock {
                        dequeuedData += dequeue().asString
                    }
                }
            }
        }

        withDistributedQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i]) } }

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

        withDistributedQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i]) } }

        repeat(subcount) {
            thread(latch) {
                withDistributedQueue(urls, queuePath) {
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
                withDistributedQueue(urls, queuePath) {
                    repeat(count / subcount) {
                        semaphore.withLock { dequeuedData += dequeue().asString }
                    }
                }
            }
        }

        withDistributedQueue(urls, queuePath) { repeat(count) { i -> enqueue(testData[i]) } }

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
        withDistributedQueue(urls, queuePath) { enqueue(token) }

        repeat(subcount) {
            thread(latch) {
                withDistributedQueue(urls, queuePath) {
                    repeat(count) {
                        val v = dequeue().asString
                        v shouldEqual token
                        enqueue(v)
                        counter.incrementAndGet()
                    }
                }
            }
        }

        latch.await()
        withDistributedQueue(urls, queuePath) {
            val v = dequeue().asString
            v shouldEqual token
        }

        counter.get() shouldEqual subcount * count
    }

    companion object : KLogging()
}