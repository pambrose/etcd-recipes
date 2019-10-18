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

package io.etcd.recipes.barrier

import com.sudothought.common.util.sleep
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.nonblockingThreads
import mu.KLogging
import org.amshove.kluent.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.seconds

class DistributedBarrierTests {

    val urls = listOf("http://localhost:2379")

    @Test
    fun badArgsTest() {
        invoking { DistributedBarrier(urls, "") } shouldThrow IllegalArgumentException::class
        invoking { DistributedBarrier(emptyList(), "something") } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun barrierTest() {
        val path = "/barriers/${javaClass.simpleName}"
        val count = 10
        val setBarrierLatch = CountDownLatch(1)
        val completeLatch = CountDownLatch(1)
        val removeBarrierTime = AtomicLong(0)
        val timeoutCount = AtomicInteger()
        val advancedCount = AtomicInteger()

        thread {
            DistributedBarrier(urls, path)
                .use { barrier ->

                    barrier.isBarrierSet shouldEqual false

                    logger.info { "Setting Barrier" }
                    val isSet = barrier.setBarrier()
                    isSet.shouldBeTrue()
                    barrier.isBarrierSet.shouldBeTrue()
                    setBarrierLatch.countDown()

                    // This should return false because barrier is already set
                    val isSet2 = barrier.setBarrier()
                    isSet2.shouldBeFalse()

                    // Pause to give time-outs a chance
                    sleep(6.seconds)

                    logger.info { "Removing Barrier" }
                    removeBarrierTime.set(System.currentTimeMillis())
                    val isRemoved = barrier.removeBarrier()
                    isRemoved.shouldBeTrue()

                    // This should return false because remove already called
                    val isRemoved2 = barrier.removeBarrier()
                    isRemoved2.shouldBeFalse()



                    sleep(3.seconds)
                }
            completeLatch.countDown()
        }

        blockingThreads(count) { i ->
            setBarrierLatch.await()
            DistributedBarrier(urls, path)
                .use { barrier ->
                    logger.info { "$i Waiting on Barrier" }
                    barrier.waitOnBarrier(1.seconds)

                    timeoutCount.incrementAndGet()

                    logger.info { "$i Timed out waiting on barrier, waiting again" }
                    barrier.waitOnBarrier()

                    // Make sure the waiter advanced quickly
                    System.currentTimeMillis() - removeBarrierTime.get() shouldBeLessThan 500
                    advancedCount.incrementAndGet()

                    logger.info { "$i Done Waiting on Barrier" }
                }
        }

        completeLatch.await()

        timeoutCount.get() shouldEqual count
        advancedCount.get() shouldEqual count

        logger.info { "Done" }
    }

    @Test
    fun earlySetBarrierTest() {
        val path = "/barriers/early${javaClass.simpleName}"
        val count = 10
        val removeBarrierTime = AtomicLong(0)
        val timeoutCount = AtomicInteger()
        val advancedCount = AtomicInteger()

        val (finishedLatch, holder) =
            nonblockingThreads(count) { i ->
                DistributedBarrier(urls, path)
                    .use { barrier ->
                        logger.info { "$i Waiting on Barrier" }
                        barrier.waitOnBarrier(1, TimeUnit.SECONDS)

                        timeoutCount.incrementAndGet()

                        logger.info { "$i Timed out waiting on barrier, waiting again" }
                        barrier.waitOnBarrier()

                        // Make sure the waiter advanced quickly
                        System.currentTimeMillis() - removeBarrierTime.get() shouldBeLessThan 500
                        advancedCount.incrementAndGet()

                        logger.info { "$i Done Waiting on Barrier" }
                    }
            }

        sleep(5.seconds)
        DistributedBarrier(urls, path)
            .use { barrier ->

                barrier.isBarrierSet shouldEqual false

                logger.info { "Setting Barrier" }
                val isSet = barrier.setBarrier()
                isSet.shouldBeTrue()
                barrier.isBarrierSet.shouldBeTrue()

                // This sould return false because barrier is already set
                val isSet2 = barrier.setBarrier()
                isSet2.shouldBeFalse()

                // Pause to give time-outs a chance
                sleep(6.seconds)

                logger.info { "Removing Barrier" }
                removeBarrierTime.set(System.currentTimeMillis())
                barrier.removeBarrier()

                sleep(3.seconds)
            }

        finishedLatch.await()

        holder.checkForException()

        timeoutCount.get() shouldEqual count
        advancedCount.get() shouldEqual count

        logger.info { "Done" }
    }

    companion object : KLogging()
}