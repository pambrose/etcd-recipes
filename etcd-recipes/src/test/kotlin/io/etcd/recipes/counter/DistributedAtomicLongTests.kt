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

package io.etcd.recipes.counter

import com.pambrose.common.util.random
import com.pambrose.common.util.sleep
import io.etcd.recipes.common.ExceptionHolder
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.threadWithExceptionCheck
import io.etcd.recipes.common.throwExceptionFromList
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds

class DistributedAtomicLongTests : StringSpec() {
    val path = "/counters/${javaClass.simpleName}"

    init {
        beforeEach {
            connectToEtcd(urls) { client ->
                DistributedAtomicLong.delete(client, path)
            }
        }

        "badArgsTest" {
            connectToEtcd(urls) { client ->
                invoking { DistributedAtomicLong(client, "") } shouldThrow IllegalArgumentException::class
            }
        }

        "defaultInitialValueTest" {
            connectToEtcd(urls) { client ->
                withDistributedAtomicLong(client, path) { get() shouldBeEqualTo 0L }
            }
        }

        "nondefaultInitialValueTest" {
            connectToEtcd(urls) { client ->
                withDistributedAtomicLong(client, path, 100L) { get() shouldBeEqualTo 100L }
            }
        }

        "incrementDecrementTest" {
            val count = 100
            connectToEtcd(urls) { client ->
                withDistributedAtomicLong(client, path) {
                    repeat(count) {
                        increment()
                        decrement()
                    }
                    get() shouldBeEqualTo 0L
                }
            }
        }

        "addSubtractTest" {
            val count = 100
            connectToEtcd(urls) { client ->
                withDistributedAtomicLong(client, path) {
                    repeat(count) {
                        add(5)
                        subtract(5)
                    }
                    get() shouldBeEqualTo 0L
                }
            }
        }

        "serialTest" {
            val count = 20
            connectToEtcd(urls) { client ->
                val counters = List(20) { DistributedAtomicLong(client, path) }
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
                total shouldBeEqualTo 0L
            }
        }

        "threaded1Test" {
            connectToEtcd(urls) { client ->
                withDistributedAtomicLong(client, path) {
                    val threadCount = 10
                    val count = 50

                    blockingThreads(threadCount) {
                        repeat(count) {
                            increment()
                            decrement()
                            add(5)
                            subtract(5)
                        }
                    }

                    get() shouldBeEqualTo 0L
                }
            }
        }

        "threaded2Test" {
            val threadCount = 10

            blockingThreads(threadCount) { i ->
                logger.debug { "Creating counter #$i" }
                connectToEtcd(urls) { client ->
                    withDistributedAtomicLong(client, path) {
                        val count = 25
                        val maxPause = 50
                        val latchList = mutableListOf<CountDownLatch>()
                        val exceptionList = mutableListOf<ExceptionHolder>()

                        val (latch0, e0) =
                            threadWithExceptionCheck {
                                logger.debug { "Begin increments for counter #$i" }
                                repeat(count) { increment() }
                                sleep(maxPause.random().milliseconds)
                                logger.debug { "Completed increments for counter #$i" }
                            }
                        latchList += latch0
                        exceptionList += e0

                        val (latch1, e1) =
                            threadWithExceptionCheck {
                                logger.debug { "Begin decrements for counter #$i" }
                                repeat(count) { decrement() }
                                sleep(maxPause.random().milliseconds)
                                logger.debug { "Completed decrements for counter #$i" }
                            }
                        latchList += latch1
                        exceptionList += e1

                        val (latch2, e2) =
                            threadWithExceptionCheck {
                                logger.debug { "Begin adds for counter #$i" }
                                repeat(count) { add(5) }
                                sleep(maxPause.random().milliseconds)
                                logger.debug { "Completed adds for counter #$i" }
                            }
                        latchList += latch2
                        exceptionList += e2

                        val (latch3, e3) =
                            threadWithExceptionCheck {
                                logger.debug { "Begin subtracts for counter #$i" }
                                repeat(count) { subtract(5) }
                                sleep(maxPause.random().milliseconds)
                                logger.debug { "Completed subtracts for counter #$i" }
                            }
                        latchList += latch3
                        exceptionList += e3

                        // Wait for all the threads to finish
                        latchList.forEach { it.await() }

                        // If an exception occurred, throw it
                        exceptionList.throwExceptionFromList()
                    }
                }
            }

            connectToEtcd(urls) { client ->
                withDistributedAtomicLong(client, path) { get() shouldBeEqualTo 0L }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
