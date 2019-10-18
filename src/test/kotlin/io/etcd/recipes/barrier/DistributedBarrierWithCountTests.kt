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

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.nonblockingThreads
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.seconds

class DistributedBarrierWithCountTests {

    val urls = listOf("http://localhost:2379")

    @Test
    fun badArgsTest() {
        invoking { DistributedBarrierWithCount(urls, "something", 0) } shouldThrow IllegalArgumentException::class
        invoking { DistributedBarrierWithCount(urls, "", 1) } shouldThrow IllegalArgumentException::class
        invoking {
            DistributedBarrierWithCount(emptyList(),
                                        "something",
                                        1)
        } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun barrierWithCountTest() {
        val path = "/barriers/${javaClass.simpleName}"
        val count = 30
        val retryAttempts = 5
        val retryLatch = CountDownLatch(count - 1)
        val retryCounter = AtomicInteger(0)
        val advancedCounter = AtomicInteger(0)

        DistributedBarrierWithCount.delete(urls, path)

        fun waiter(id: Int, barrier: DistributedBarrierWithCount, retryCount: Int = 0) {

            sleep(5.random.seconds)
            logger.info { "#$id Waiting on barrier" }

            repeat(retryCount) {
                barrier.waitOnBarrier(1.seconds)
                logger.info { "#$id Timed out waiting on barrier, waiting again" }
                retryCounter.incrementAndGet()
            }

            retryLatch.countDown()

            logger.info { "#$id Waiter count = ${barrier.waiterCount}" }
            barrier.waitOnBarrier()

            advancedCounter.incrementAndGet()

            logger.info { "#$id Done waiting on barrier" }
        }

        val (finishedLatch, holder) =
            nonblockingThreads(count - 1) { i ->
                DistributedBarrierWithCount(urls, path, count)
                    .use { barrier ->
                        waiter(i, barrier, retryAttempts)
                    }
            }

        retryLatch.await()
        sleep(2.seconds)

        DistributedBarrierWithCount(urls, path, count)
            .use { barrier ->
                waiter(99, barrier)
            }

        finishedLatch.await()

        holder.checkForException()

        retryCounter.get() shouldEqual retryAttempts * (count - 1)
        advancedCounter.get() shouldEqual count

        logger.info { "Done" }
    }

    companion object : KLogging()
}