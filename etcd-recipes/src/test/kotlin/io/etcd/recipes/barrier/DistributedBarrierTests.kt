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

package io.etcd.recipes.barrier

import com.pambrose.common.concurrent.thread
import com.pambrose.common.util.sleep
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.nonblockingThreads
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.seconds

class DistributedBarrierTests : StringSpec() {
    init {
        "badArgsTest" {
            connectToEtcd(urls) { client ->
                shouldThrow<IllegalArgumentException> { DistributedBarrier(client, "") }
            }
        }

        "barrierTest" {
            val path = "/barriers/DistributedBarrierTests"
            val count = 10
            val setBarrierLatch = CountDownLatch(1)
            val completeLatch = CountDownLatch(1)
            val removeBarrierTime = AtomicLong(0L)
            val timeoutCount = AtomicInt(0)
            val advancedCount = AtomicInt(0)

            connectToEtcd(urls) { client ->

                thread(completeLatch) {
                    withDistributedBarrier(client, path) {
                        isBarrierSet() shouldBe false

                        logger.debug { "Setting Barrier" }
                        val isSet = setBarrier()
                        isSet.shouldBeTrue()
                        isBarrierSet().shouldBeTrue()
                        setBarrierLatch.countDown()

                        // This should return false because barrier is already set
                        val isSet2 = setBarrier()
                        isSet2.shouldBeFalse()

                        // Pause to give time-outs a chance
                        sleep(6.seconds)

                        logger.info { "Removing Barrier" }
                        removeBarrierTime.store(System.currentTimeMillis())
                        val isRemoved = removeBarrier()
                        isRemoved.shouldBeTrue()

                        // This should return false because remove already called
                        val isRemoved2 = removeBarrier()
                        isRemoved2.shouldBeFalse()

                        sleep(3.seconds)
                    }
                }

                blockingThreads(count) { i ->
                    setBarrierLatch.await()
                    withDistributedBarrier(client, path) {
                        logger.info { "$i Waiting on Barrier" }
                        waitOnBarrier(1.seconds)

                        timeoutCount.incrementAndFetch()

                        logger.info { "$i Timed out waiting on barrier, waiting again" }
                        waitOnBarrier()

                        // Make sure the waiter advanced quickly
                        System.currentTimeMillis() - removeBarrierTime.load() shouldBeLessThan 500L
                        advancedCount.incrementAndFetch()

                        logger.debug { "$i Done Waiting on Barrier" }
                    }
                }
            }

            completeLatch.await()

            timeoutCount.load() shouldBe count
            advancedCount.load() shouldBe count

            logger.debug { "Done" }
        }

        "earlySetBarrierTest" {
            val path = "/barriers/earlyDistributedBarrierTests"
            val count = 10
            val removeBarrierTime = AtomicLong(0L)
            val timeoutCount = AtomicInt(0)
            val advancedCount = AtomicInt(0)

            connectToEtcd(urls) { client ->

                val (finishedLatch, holder) =
                    nonblockingThreads(count) { i ->
                        withDistributedBarrier(client, path) {
                            logger.debug { "$i Waiting on Barrier" }
                            waitOnBarrier(1, TimeUnit.SECONDS)

                            timeoutCount.incrementAndFetch()

                            logger.debug { "$i Timed out waiting on barrier, waiting again" }
                            waitOnBarrier()

                            // Make sure the waiter advanced quickly
                            System.currentTimeMillis() - removeBarrierTime.load() shouldBeLessThan 500L
                            advancedCount.incrementAndFetch()

                            logger.debug { "$i Done Waiting on Barrier" }
                        }
                    }

                sleep(5.seconds)

                withDistributedBarrier(client, path) {
                    isBarrierSet() shouldBe false

                    logger.debug { "Setting Barrier" }
                    val isSet = setBarrier()
                    isSet.shouldBeTrue()
                    isBarrierSet().shouldBeTrue()

                    // This sould return false because barrier is already set
                    val isSet2 = setBarrier()
                    isSet2.shouldBeFalse()

                    // Pause to give time-outs a chance
                    sleep(6.seconds)

                    logger.debug { "Removing Barrier" }
                    removeBarrierTime.store(System.currentTimeMillis())
                    removeBarrier()

                    sleep(3.seconds)
                }

                finishedLatch.await()

                holder.checkForException()
            }

            timeoutCount.load() shouldBe count
            advancedCount.load() shouldBe count

            logger.debug { "Done" }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
