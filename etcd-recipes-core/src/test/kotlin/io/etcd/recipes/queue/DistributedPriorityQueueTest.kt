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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DistributedPriorityQueueTest : StringSpec() {
    // Namespace etcd paths by class name so concurrent test forks (and the
    // structurally similar DistributedQueueTest) cannot collide.
    private val basePath = "/queue/${this::class.simpleName}"
    private val iterCount = 100
    private val threadCount = 10
    private val testData = List(iterCount) { "V %04d".format(it) }

    private fun threadedTestNoWait(
      iterCount: Int,
      threadCount: Int,
    ) {
        val queuePath = "$basePath/threadedTestNoWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData: MutableList<String> = []
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldBe 0

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

            client.getChildCount(queuePath) shouldBe 0
        }

        assertDequeued(dequeuedData, testData, threadCount)
    }

    // A single consumer dequeues in deterministic FIFO order, so assert it
    // exactly. With multiple concurrent consumers the global dequeue order is
    // not guaranteed (each consumer races to append to the shared list, and a
    // priority queue does not promise strict ordering across concurrent
    // readers) — there the meaningful invariant is that every item is dequeued
    // exactly once.
    private fun assertDequeued(
      dequeued: List<String>,
      expected: List<String>,
      threadCount: Int,
    ) {
        dequeued.size shouldBe expected.size
        if (threadCount == 1)
            dequeued shouldBe expected
        else
            dequeued shouldContainExactlyInAnyOrder expected
    }

    private fun threadedTestWithWait(
      iterCount: Int,
      threadCount: Int,
    ) {
        val queuePath = "$basePath/threadedTestWithWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData: MutableList<String> = synchronizedList([])
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldBe 0

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

            client.getChildCount(queuePath) shouldBe 0
        }

        // The producer/consumer latch has already drained — no settle needed.
        assertDequeued(dequeuedData, testData, threadCount)
    }

    init {
        "serialTestNoWait" {
            val queuePath = "$basePath/serialTestNoWait"
            val dequeuedData: MutableList<String> = []

            connectToEtcd(urls) { client ->
                client.getChildCount(queuePath) shouldBe 0
                withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], 1u) } }
                withDistributedPriorityQueue(client, queuePath) {
                  repeat(iterCount) { dequeuedData += dequeue().asString }
                }
                client.getChildCount(queuePath) shouldBe 0
            }

            dequeuedData.size shouldBe testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBe testData[i] }
            dequeuedData shouldBe testData
        }

        "serialTestWithWait" {
            val queuePath = "$basePath/serialPriorityTestWithWait"
            val dequeuedData: MutableList<String> = []
            val dequeueLatch = CountDownLatch(1)
            val enqueueLatch = CountDownLatch(1)

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBe 0
            }

            thread(dequeueLatch) {
                connectToEtcd(urls) { client ->
                    withDistributedPriorityQueue(client, queuePath) {
                        repeat(iterCount) {
                            dequeuedData += dequeue().asString.also { logger.info { "Dequeueing $it" } }
                        }
                    }
                    logger.info { "Dequeue complete" }

                    client.getChildCount(queuePath) shouldBe 0
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

            dequeuedData.size shouldBe testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBe testData[i] }
            dequeuedData shouldBe testData
        }

        // #19: a consumer parked on an empty queue must deliver every item of a
        // mixed-priority burst exactly once via the re-query-on-wake path. Strict
        // ordering of the woken item comes from the same getFirstChild head-selection
        // that serialTestNoWaitWithPriorities verifies; the burst-arrival race itself
        // is not deterministically orderable, so this guards delivery, not order.
        "emptyWaitDeliversEveryMixedPriorityItemExactlyOnce" {
            val queuePath = "$basePath/emptyWaitMixedPriority"
            val count = 25
            val values = List(count) { "mp%03d".format(it) }
            val dequeued: MutableList<String> = synchronizedList([])
            val dequeueLatch = CountDownLatch(1)
            val enqueueLatch = CountDownLatch(1)
            val consumerReady = CountDownLatch(1)

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBe 0
            }

            thread(dequeueLatch) {
                connectToEtcd(urls) { client ->
                    withDistributedPriorityQueue(client, queuePath) {
                        consumerReady.countDown()
                        repeat(count) { dequeued += dequeue().asString }
                    }
                }
            }

            thread(enqueueLatch) {
                connectToEtcd(urls) { client ->
                    consumerReady.await()
                    // Let the consumer reach the empty-queue wait, then burst-enqueue
                    // distinct priorities in descending order (the head is enqueued last).
                    sleep(2.seconds)
                    withDistributedPriorityQueue(client, queuePath) {
                        values.forEachIndexed { i, v -> enqueue(v, count - i) }
                    }
                }
            }

            dequeueLatch.await()
            enqueueLatch.await()

            dequeued shouldContainExactlyInAnyOrder values
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
        "threadedTestWithWait1" {
            threadedTestWithWait(10, 1)
            threadedTestWithWait(10, 2)
            threadedTestWithWait(10, 5)
            threadedTestWithWait(10, 10)
        }

        "threadedTestWithWait2" {
            threadedTestWithWait(100, 1)
            threadedTestWithWait(100, 2)
            threadedTestWithWait(100, 5)
            threadedTestWithWait(100, 10)
        }

        "threadedTestWithWait3" {
            threadedTestWithWait(iterCount, threadCount)
        }

        // Disabled under Kotest: 10 dequeue watchers on the same path appear to deadlock.
        // Same code passed under JUnit — root cause not yet diagnosed.
        "pingPongTest" {
            val queuePath = "$basePath/pingPongTest"
            val counter = AtomicInt(0)
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
                                v shouldBe token
                                enqueue(v, 1u)
                                counter.incrementAndFetch()
                            }
                        }
                    }
                }

                latch.await()

                withDistributedPriorityQueue(client, queuePath) {
                    val v = dequeue().asString
                    v shouldBe token
                }
            }

            counter.load() shouldBe threadCount * iterCount
        }

        "serialTestNoWaitWithPriorities" {
            val queuePath = "$basePath/serialTestNoWaitWithPriorities"
            val dequeuedData: MutableList<String> = []

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBe 0
                withDistributedPriorityQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i], i) } }
                withDistributedPriorityQueue(client, queuePath) {
                  repeat(iterCount) { dequeuedData += dequeue().asString }
                }
                client.getChildCount(queuePath) shouldBe 0
            }

            dequeuedData.size shouldBe testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBe testData[i] }
            dequeuedData shouldBe testData
        }

        "serialTestNoWaitWithReversedPriorities" {
            val queuePath = "$basePath/serialTestNoWaitWithReversedPriorities"
            val dequeuedData: MutableList<String> = []

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBe 0
                withDistributedPriorityQueue(client, queuePath) {
                    repeat(iterCount) { i -> enqueue(testData[i], (iterCount - i)) }
                }
                withDistributedPriorityQueue(client, queuePath) {
                    repeat(iterCount) { dequeuedData += dequeue().asString }
                }
                client.getChildCount(queuePath) shouldBe 0
            }

            dequeuedData.size shouldBe testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBe testData[iterCount - i - 1] }
            dequeuedData shouldBe testData.reversed()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
