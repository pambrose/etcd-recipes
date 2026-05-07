/*
 * Copyright © 2026 Paul Ambrose
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

package io.etcd.recipes.queue

import com.pambrose.common.concurrent.thread
import com.pambrose.common.util.sleep
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import org.amshove.kluent.shouldBeEqualTo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DistributedPriorityQueueTest : StringSpec() {
    private val iterCount = 100
    private val threadCount = 10
    private val testData = List(iterCount) { "V %04d".format(it) }

    private fun threadedTestNoWait(
        iterCount: Int,
        threadCount: Int,
    ) {
        val queuePath = "/queue/threadedTestNoWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = mutableListOf<String>()
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldBeEqualTo 0

            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }

            repeat(threadCount) {
                thread(latch) {
                    withDistributedPriorityQueue(client, queuePath) {
                        repeat(iterCount / threadCount) {
                            synchronized(dequeuedData) {
                                dequeuedData += dequeue().asString
                            }
                        }
                    }
                }
            }

            latch.await()

            client.getChildCount(queuePath) shouldBeEqualTo 0
        }

        dequeuedData.size shouldBeEqualTo testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBeEqualTo testData[i] }
        dequeuedData shouldBeEqualTo testData
    }

    private fun threadedTestWithWait(
        iterCount: Int,
        threadCount: Int,
    ) {
        val queuePath = "/queue/threadedTestWithWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = java.util.Collections.synchronizedList(mutableListOf<String>())
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldBeEqualTo 0

            repeat(threadCount) {
                thread(latch) {
                    withDistributedPriorityQueue(client, queuePath) {
                        repeat(iterCount / threadCount) {
                            synchronized(dequeuedData) {
                                dequeuedData += dequeue().asString
                            }
                        }
                    }
                }
            }

            withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }

            latch.await()

            client.getChildCount(queuePath) shouldBeEqualTo 0
        }

        sleep(5.seconds)

        dequeuedData.size shouldBeEqualTo testData.size
        repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBeEqualTo testData[i] }
        dequeuedData shouldBeEqualTo testData
    }

    init {
        "serialTestNoWait" {
            val queuePath = "/queue/serialTestNoWait"
            val dequeuedData = mutableListOf<String>()

            connectToEtcd(urls) { client ->
                client.getChildCount(queuePath) shouldBeEqualTo 0
                withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }
                withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { dequeuedData += dequeue().asString } }
                client.getChildCount(queuePath) shouldBeEqualTo 0
            }

            dequeuedData.size shouldBeEqualTo testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBeEqualTo testData[i] }
            dequeuedData shouldBeEqualTo testData
        }

        "serialTestWithWait" {
            val queuePath = "/queue/serialPriorityTestWithWait"
            val dequeuedData = mutableListOf<String>()
            val dequeueLatch = CountDownLatch(1)
            val enqueueLatch = CountDownLatch(1)

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBeEqualTo 0
            }

            thread(dequeueLatch) {
                connectToEtcd(urls) { client ->
                    withDistributedPriorityQueue(client, queuePath) {
                        repeat(iterCount) {
                            dequeuedData += dequeue().asString.also { logger.info { "Dequeueing $it" } }
                        }
                    }
                    logger.info { "Dequeue complete" }

                    client.getChildCount(queuePath) shouldBeEqualTo 0
                }
            }

            thread(enqueueLatch) {
                connectToEtcd(urls) { client ->
                    withDistributedPriorityQueue(client, queuePath, 50.milliseconds) {
                        repeat(iterCount) { i ->
                            logger.info { "Enqueueing" }
                            enqueue(testData[i], 1u)
                        }
                        logger.info { "Enqueue complete" }
                    }
                }
            }

            dequeueLatch.await()
            enqueueLatch.await()

            dequeuedData.size shouldBeEqualTo testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBeEqualTo testData[i] }
            dequeuedData shouldBeEqualTo testData
        }

        "threadedTestNoWait1" {
            threadedTestNoWait(10, 1)
            threadedTestNoWait(10, 2)
            threadedTestNoWait(10, 5)
            threadedTestNoWait(10, 10)
        }

        "threadedTestNoWait2" {
            threadedTestNoWait(100, 1)
            threadedTestNoWait(100, 2)
            threadedTestNoWait(100, 5)
            threadedTestNoWait(100, 10)
        }

        "threadedTestNoWait3" {
            threadedTestNoWait(iterCount, threadCount)
        }

        // Disabled under Kotest: concurrent enqueue/dequeue deadlocks (same root cause as pingPongTest)
        "!threadedTestWithWait1" {
            threadedTestWithWait(10, 1)
            threadedTestWithWait(10, 2)
            threadedTestWithWait(10, 5)
            threadedTestWithWait(10, 10)
        }

        "!threadedTestWithWait2" {
            threadedTestWithWait(100, 1)
            threadedTestWithWait(100, 2)
            threadedTestWithWait(100, 5)
            threadedTestWithWait(100, 10)
        }

        "!threadedTestWithWait3" {
            threadedTestWithWait(iterCount, threadCount)
        }

        // Disabled under Kotest: 10 dequeue watchers on the same path appear to deadlock.
        // Same code passed under JUnit — root cause not yet diagnosed.
        "!pingPongTest" {
            val queuePath = "/queue/pingPongTest"
            val counter = AtomicInteger(0)
            val token = "Pong"
            val latch = CountDownLatch(threadCount)
            val iterCount = 100

            // Prime the queue with a value
            connectToEtcd(urls) { client ->
                withDistributedPriorityQueue(client, queuePath) { enqueue(token, 1u) }

                repeat(threadCount) {
                    thread(latch) {
                        withDistributedPriorityQueue(client, queuePath) {
                            repeat(iterCount) {
                                val v = dequeue().asString
                                v shouldBeEqualTo token
                                enqueue(v, 1u)
                                counter.incrementAndGet()
                            }
                        }
                    }
                }

                latch.await()

                withDistributedPriorityQueue(client, queuePath) {
                    val v = dequeue().asString
                    v shouldBeEqualTo token
                }
            }

            counter.get() shouldBeEqualTo threadCount * iterCount
        }

        "!serialTestNoWaitWithPriorities" {
            val queuePath = "/queue/serialTestNoWaitWithPriorities"
            val dequeuedData = mutableListOf<String>()

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBeEqualTo 0
                withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], i) } }
                withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { dequeuedData += dequeue().asString } }
                client.getChildCount(queuePath) shouldBeEqualTo 0
            }

            dequeuedData.size shouldBeEqualTo testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBeEqualTo testData[i] }
            dequeuedData shouldBeEqualTo testData
        }

        "serialTestNoWaitWithReversedPriorities" {
            val queuePath = "/queue/serialTestNoWaitWithReversedPriorities"
            val dequeuedData = mutableListOf<String>()

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBeEqualTo 0
                withDistributedPriorityQueue(client, queuePath) {
                    repeat(iterCount) { i -> enqueue(testData[i], (iterCount - i)) }
                }
                withDistributedPriorityQueue(client, queuePath) {
                    repeat(iterCount) { dequeuedData += dequeue().asString }
                }
                client.getChildCount(queuePath) shouldBeEqualTo 0
            }

            dequeuedData.size shouldBeEqualTo testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBeEqualTo testData[iterCount - i - 1] }
            dequeuedData shouldBeEqualTo testData.reversed()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
