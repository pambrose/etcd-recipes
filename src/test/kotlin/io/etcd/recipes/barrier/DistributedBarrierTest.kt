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

package io.etcd.recipes.barrier

import com.sudothought.common.util.sleep
import io.etcd.recipes.common.blockingThreads
import org.amshove.kluent.shouldBeLessThan
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.time.seconds

class DistributedBarrierTest {

    @Test
    fun barrierTest() {
        val urls = listOf("http://localhost:2379")
        val barrierPath = "/barriers/threadedclients"
        val count = 5
        val setBarrierLatch = CountDownLatch(1)
        val completeLatch = CountDownLatch(1)
        val removeBarrierTime = AtomicLong(0)
        val timeoutCount = AtomicInteger()
        val advancedCount = AtomicInteger()

        thread {
            DistributedBarrier(urls, barrierPath)
                .use { barrier ->
                    println("Setting Barrier")
                    barrier.setBarrier()
                    setBarrierLatch.countDown()

                    sleep(6.seconds)

                    println("Removing Barrier")
                    barrier.removeBarrier()
                    removeBarrierTime.set(System.currentTimeMillis())

                    sleep(3.seconds)
                }
            completeLatch.countDown()
        }

        blockingThreads(count) { i ->
            setBarrierLatch.await()
            DistributedBarrier(urls, barrierPath)
                .use { barrier ->
                    println("$i Waiting on Barrier")
                    barrier.waitOnBarrier(1.seconds)

                    timeoutCount.incrementAndGet()

                    println("$i Timed out waiting on barrier, waiting again")
                    barrier.waitOnBarrier()

                    // Make sure the waiter advanced in less than 3 secs
                    abs(System.currentTimeMillis() - removeBarrierTime.get()) shouldBeLessThan 500
                    println(abs(System.currentTimeMillis() - removeBarrierTime.get()))
                    advancedCount.incrementAndGet()

                    println("$i Done Waiting on Barrier")
                }
        }

        completeLatch.await()

        timeoutCount.get() shouldEqual count
        advancedCount.get() shouldEqual count

        println("Done")
    }
}