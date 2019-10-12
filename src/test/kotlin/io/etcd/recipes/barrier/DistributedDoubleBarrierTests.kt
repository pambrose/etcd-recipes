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
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.milliseconds
import kotlin.time.seconds

class DistributedDoubleBarrierTests {

    @Test
    fun main() {
        val urls = listOf("http://localhost:2379")
        val path = "/barriers/${javaClass.simpleName}"
        val count = 10
        val retryAttempts = 5
        val enterLatch = CountDownLatch(count - 1)
        val leaveLatch = CountDownLatch(count - 1)
        val doneLatch = CountDownLatch(count)

        val enterRetryCounter = AtomicInteger(0)
        val leaveRetryCounter = AtomicInteger(0)
        val enterCounter = AtomicInteger(0)
        val leaveCounter = AtomicInteger(0)

        DistributedDoubleBarrier.delete(urls, path)

        fun enterBarrier(id: Int, barrier: DistributedDoubleBarrier, retryCount: Int = 0) {
            sleep(5.random.seconds)

            repeat(retryCount) {
                println("#$id Waiting to enter barrier")
                if (it % 2 == 0)
                    barrier.enter(1000.random.milliseconds)
                else
                    barrier.enter(1000, TimeUnit.MILLISECONDS)
                enterRetryCounter.incrementAndGet()
                println("#$id Timed out entering barrier")
            }

            enterLatch.countDown()
            println("#$id Waiting to enter barrier")
            barrier.enter()
            enterCounter.incrementAndGet()

            println("#$id Entered barrier")
        }

        fun leaveBarrier(id: Int, barrier: DistributedDoubleBarrier, retryCount: Int = 0) {
            sleep(10.random.seconds)

            repeat(retryCount) {
                println("#$id Waiting to leave barrier")
                if (it % 2 == 0)
                    barrier.leave(1000.random.milliseconds)
                else
                    barrier.leave(1000, TimeUnit.MILLISECONDS)
                leaveRetryCounter.incrementAndGet()
                println("#$id Timed out leaving barrier")
            }

            leaveLatch.countDown()
            println("#$id Waiting to leave barrier")
            barrier.leave()
            leaveCounter.incrementAndGet()
            println("#$id Left barrier")

            doneLatch.countDown()
        }

        val (finishedLatch, holder) =
            nonblockingThreads(count - 1) { i ->
                DistributedDoubleBarrier(urls, path, count)
                    .use { barrier ->
                        enterBarrier(i, barrier, retryAttempts)
                        sleep(5.random.seconds)
                        leaveBarrier(i, barrier, retryAttempts)
                    }
            }

        DistributedDoubleBarrier(urls, path, count)
            .use { barrier ->
                enterLatch.await()
                sleep(2.seconds)
                barrier.enterWaiterCount shouldEqual count - 1
                enterBarrier(99, barrier)

                leaveLatch.await()
                sleep(2.seconds)

                barrier.leaveWaiterCount shouldEqual count - 1
                leaveBarrier(99, barrier)
            }

        doneLatch.await()

        holder.checkForException()

        enterRetryCounter.get() shouldEqual retryAttempts * (count - 1)
        enterCounter.get() shouldEqual count

        leaveRetryCounter.get() shouldEqual retryAttempts * (count - 1)
        leaveCounter.get() shouldEqual count

        println("Done")
    }
}