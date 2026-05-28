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
import java.util.concurrent.atomic.AtomicInteger

class DistributedQueueTest : StringSpec() {
    // Namespace etcd paths by class name so concurrent test forks (and the
    // structurally similar DistributedPriorityQueueTest) cannot collide.
    private val basePath = "/queue/${this::class.simpleName}"
    private val iterCount = 200
    private val threadCount = 10
    private val testData = List(iterCount) { "V $it" }

    private fun threadedTestNoWait(
      iterCount: Int,
      threadCount: Int,
    ) {
        val queuePath = "$basePath/threadedTestNoWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = synchronizedList(mutableListOf<String>())
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldBe 0

            withDistributedQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i]) } }

            repeat(threadCount) {
                thread(latch) {
                    withDistributedQueue(client, queuePath) {
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

    private fun threadedTestWithWait(
      iterCount: Int,
      threadCount: Int,
    ) {
        val queuePath = "$basePath/threadedTestWithWait"
        val latch = CountDownLatch(threadCount)
        val dequeuedData = synchronizedList(mutableListOf<String>())
        val testData = List(iterCount) { "V %04d".format(it) }

        connectToEtcd(urls) { client ->
            client.deleteChildren(queuePath)
            client.getChildCount(queuePath) shouldBe 0

            repeat(threadCount) {
                thread(latch) {
                    withDistributedQueue(client, queuePath) {
                        repeat(iterCount / threadCount) {
                            synchronized(dequeuedData) {
                                dequeuedData += dequeue().asString
                            }
                        }
                    }
                }
            }

            withDistributedQueue(client, queuePath) {
                repeat(iterCount) { i ->
                    enqueue(testData[i])
                }
            }

            latch.await()

            client.getChildCount(queuePath) shouldBe 0
        }

        // The producer/consumer latch has already drained — no settle needed.
        assertDequeued(dequeuedData, testData, threadCount)
    }

    // A single consumer dequeues in deterministic FIFO order, so assert it
    // exactly. With multiple concurrent consumers the global dequeue order is
    // not guaranteed (each consumer races to append to the shared list) — there
    // the meaningful invariant is that every item is dequeued exactly once.
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

    init {
        "serialTestNoWait" {
            val queuePath = "$basePath/serialTestNoWait"
            val dequeuedData = mutableListOf<String>()

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBe 0
                withDistributedQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i]) } }
                withDistributedQueue(client, queuePath) { repeat(iterCount) { dequeuedData += dequeue().asString } }
                client.getChildCount(queuePath) shouldBe 0
            }

            dequeuedData.size shouldBe testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBe testData[i] }
            dequeuedData shouldBe testData
        }

        // Disabled under Kotest: enqueue+dequeue threads on same client deadlock
        "serialTestWithWait" {
            val queuePath = "$basePath/serialTestWithWait"
            val dequeuedData = synchronizedList(mutableListOf<String>())
            val latch = CountDownLatch(1)

            connectToEtcd(urls) { client ->
                client.deleteChildren(queuePath)
                client.getChildCount(queuePath) shouldBe 0

                thread(latch) {
                    withDistributedQueue(client, queuePath) {
                        repeat(iterCount) { dequeuedData += dequeue().asString }
                    }
                }

                withDistributedQueue(client, queuePath) { repeat(iterCount) { i -> enqueue(testData[i]) } }

                latch.await()

                client.getChildCount(queuePath) shouldBe 0
            }

            dequeuedData.size shouldBe testData.size
            repeat(dequeuedData.size) { i -> dequeuedData[i] shouldBe testData[i] }
            dequeuedData shouldBe testData
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
            val counter = AtomicInteger(0)
            val token = "Pong"
            val latch = CountDownLatch(threadCount)
            val iterCount = 100

            // Prime the queue with a value
            connectToEtcd(urls) { client ->
                withDistributedQueue(client, queuePath) { enqueue(token) }

                repeat(threadCount) {
                    thread(latch) {
                        withDistributedQueue(client, queuePath) {
                            repeat(iterCount) {
                                val v = dequeue().asString
                                v shouldBe token
                                enqueue(v)
                                counter.incrementAndGet()
                            }
                        }
                    }
                }

                latch.await()

                withDistributedQueue(client, queuePath) {
                    val v = dequeue().asString
                    v shouldBe token
                }
            }

            counter.get() shouldBe threadCount * iterCount
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
