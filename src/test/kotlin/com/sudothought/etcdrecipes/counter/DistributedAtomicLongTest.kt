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

package com.sudothought.etcdrecipes.counter

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.milliseconds

class DistributedAtomicLongTest {
    val urls = listOf("http://localhost:2379")
    val path = "/DistributedAtomicLongTest"

    @BeforeEach
    fun deleteCounter() = DistributedAtomicLong.delete(urls, path)

    @Test
    fun defaultInitialValueTest() {
        DistributedAtomicLong(urls, path).use { counter -> counter.get() shouldEqual 0L }
    }

    @Test
    fun nondefaultInitialValueTest() {
        DistributedAtomicLong(urls, path, 100L).use { counter -> counter.get() shouldEqual 100L }
    }

    @Test
    fun incrementDecrementTest() {
        val count = 100
        DistributedAtomicLong(urls, path)
            .use { counter ->
                repeat(count) {
                    counter.increment()
                    counter.decrement()
                }
                counter.get() shouldEqual 0L
            }
    }

    @Test
    fun addSubtractTest() {
        val count = 100
        DistributedAtomicLong(urls, path)
            .use { counter ->
                repeat(count) {
                    counter.add(5)
                    counter.subtract(5)
                }
                counter.get() shouldEqual 0L
            }
    }

    @Test
    fun serialTest() {
        val count = 20
        val counters = List(20) { DistributedAtomicLong(urls, path) }
        val total =
            counters
                .onEach { counter ->
                    repeat(count) { counter.increment() }
                    repeat(count) { counter.decrement() }
                    repeat(count) { counter.add(5) }
                    repeat(count) { counter.subtract(5) }
                }
                .first()
                .get()

        counters.forEach { it.close() }
        total shouldEqual 0L
    }

    @Test
    fun threaded1Test() {
        DistributedAtomicLong(urls, path)
            .use { counter ->
                val threadCount = 5
                val count = 50
                val latch = CountDownLatch(threadCount)

                repeat(threadCount) {
                    thread {
                        repeat(count) {
                            counter.increment()
                            counter.decrement()
                            counter.add(5)
                            counter.subtract(5)
                        }
                        latch.countDown()
                    }
                }
                latch.await()
                counter.get() shouldEqual 0L
            }
    }

    @Test
    fun threaded2Test() {
        val threadCount = 10
        val outerLatch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            thread {
                println("Creating counter #$i")
                DistributedAtomicLong(urls, path)
                    .use { counter ->
                        val innerLatch = CountDownLatch(4)
                        val count = 25
                        val maxPause = 50

                        thread {
                            println("Begin increments for counter #$i")
                            repeat(count) { counter.increment() }
                            sleep(maxPause.random.milliseconds)
                            innerLatch.countDown()
                            println("Completed increments for counter #$i")
                        }

                        thread {
                            println("Begin decrements for counter #$i")
                            repeat(count) { counter.decrement() }
                            sleep(maxPause.random.milliseconds)
                            innerLatch.countDown()
                            println("Completed decrements for counter #$i")
                        }

                        thread {
                            println("Begin adds for counter #$i")
                            repeat(count) { counter.add(5) }
                            sleep(maxPause.random.milliseconds)
                            innerLatch.countDown()
                            println("Completed adds for counter #$i")
                        }

                        thread {
                            println("Begin subtracts for counter #$i")
                            repeat(count) { counter.subtract(5) }
                            sleep(maxPause.random.milliseconds)
                            innerLatch.countDown()
                            println("Completed subtracts for counter #$i")
                        }

                        innerLatch.await()
                    }

                outerLatch.countDown()
            }
        }

        outerLatch.await()

        DistributedAtomicLong(urls, path).use { counter -> counter.get() shouldEqual 0L }
    }
}

