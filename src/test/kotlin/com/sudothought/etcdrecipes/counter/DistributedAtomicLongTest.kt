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

import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class DistributedAtomicLongTest {
    val count = 100


    @Test
    fun initialValueTest() {
        DistributedAtomicLong(url, path).use { counter -> counter.get() shouldEqual 0L }
        resetCounter()

        DistributedAtomicLong(url, path, 100L).use { counter -> counter.get() shouldEqual 100L }
        resetCounter()
    }


    @Test
    fun incrementDecrementTest() {
        DistributedAtomicLong(url, path)
            .use { counter ->
                repeat(count) {
                    counter.increment()
                    counter.decrement()
                }
                counter.get() shouldEqual 0L
            }

        resetCounter()
    }

    @Test
    fun addSubtractTest() {
        DistributedAtomicLong(url, path)
            .use { counter ->
                repeat(count) {
                    counter.add(5)
                    counter.subtract(5)
                }
                counter.get() shouldEqual 0L
            }

        resetCounter()
    }

    @Test
    fun threadedTest() {
        DistributedAtomicLong(url, path)
            .use { counter ->
                val threadCount = 5
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

        resetCounter()
    }


    companion object {
        val url = "http://localhost:2379"
        val path = "/countertest"

        fun resetCounter() = DistributedAtomicLong.reset(url, path)

        @JvmStatic
        @BeforeAll
        fun setup() {
            resetCounter()
        }
    }
}

