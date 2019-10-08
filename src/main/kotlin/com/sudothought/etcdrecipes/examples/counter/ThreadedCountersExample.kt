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

package com.sudothought.etcdrecipes.examples.counter

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import com.sudothought.etcdrecipes.counter.DistributedAtomicLong
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.measureTimedValue
import kotlin.time.milliseconds

fun main() {
    val url = "http://localhost:2379"
    val counterPath = "counter2"
    val threadCount = 10
    val outerLatch = CountDownLatch(threadCount)

    DistributedAtomicLong.delete(url, counterPath)

    val (_, dur) =
        measureTimedValue {
            repeat(threadCount) { i ->
                thread {
                    println("Creating counter #$i")
                    DistributedAtomicLong(url, counterPath)
                        .use { counter ->
                            val innerLatch = CountDownLatch(4)
                            val count = 50
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
        }

    DistributedAtomicLong(url, counterPath).use { println("Counter value = ${it.get()} in $dur") }
}