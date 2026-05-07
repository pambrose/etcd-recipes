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

import com.pambrose.common.util.random
import com.pambrose.common.util.sleep
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.nonblockingThreads
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DistributedDoubleBarrierTests : StringSpec() {
    init {
        "badArgsTest" {
            connectToEtcd(urls) { client ->
                shouldThrow<IllegalArgumentException> { DistributedDoubleBarrier(client, "something", 0) }
                shouldThrow<IllegalArgumentException> { DistributedDoubleBarrier(client, "", 1) }
            }
        }

        "barrierTest" {
            val path = "/barriers/DistributedDoubleBarrierTests"
            val count = 10
            val retryAttempts = 5
            val enterLatch = CountDownLatch(count - 1)
            val leaveLatch = CountDownLatch(count - 1)
            val doneLatch = CountDownLatch(count)

            val enterRetryCounter = AtomicInteger(0)
            val leaveRetryCounter = AtomicInteger(0)
            val enterCounter = AtomicInteger(0)
            val leaveCounter = AtomicInteger(0)

            fun enterBarrier(
                id: Int,
                barrier: DistributedDoubleBarrier,
                retryCount: Int = 0,
            ) {
                sleep(5.random().seconds)

                repeat(retryCount) {
                    logger.debug { "#$id Waiting to enter barrier" }
                    if (it % 2 == 0)
                        barrier.enter(1000.random().milliseconds)
                    else
                        barrier.enter(1000, TimeUnit.MILLISECONDS)
                    enterRetryCounter.incrementAndGet()
                    logger.debug { "#$id Timed out entering barrier" }
                }

                enterLatch.countDown()
                logger.debug { "#$id Waiting to enter barrier" }
                barrier.enter()
                enterCounter.incrementAndGet()

                logger.debug { "#$id Entered barrier" }
            }

            fun leaveBarrier(
                id: Int,
                barrier: DistributedDoubleBarrier,
                retryCount: Int = 0,
            ) {
                sleep(10.random().seconds)

                repeat(retryCount) {
                    logger.debug { "#$id Waiting to leave barrier" }
                    if (it % 2 == 0)
                        barrier.leave(1000.random().milliseconds)
                    else
                        barrier.leave(1000, TimeUnit.MILLISECONDS)
                    leaveRetryCounter.incrementAndGet()
                    logger.debug { "#$id Timed out leaving barrier" }
                }

                leaveLatch.countDown()
                logger.debug { "#$id Waiting to leave barrier" }
                barrier.leave()
                leaveCounter.incrementAndGet()
                logger.debug { "#$id Left barrier" }
                doneLatch.countDown()
            }

            connectToEtcd(urls) { client ->

                // Clean up leftover children
                client.deleteChildren(path)

                val (finishedLatch, holder) =
                    nonblockingThreads(count - 1) { i ->
                        withDistributedDoubleBarrier(client, path, count) {
                            enterBarrier(i, this, retryAttempts)
                            sleep(5.random().seconds)
                            leaveBarrier(i, this, retryAttempts)
                        }
                    }

                withDistributedDoubleBarrier(client, path, count) {
                    enterLatch.await()
                    sleep(2.seconds)

                    enterWaiterCount.toInt() shouldBe count - 1
                    enterBarrier(99, this)

                    leaveLatch.await()
                    sleep(2.seconds)

                    leaveWaiterCount.toInt() shouldBe count - 1
                    leaveBarrier(99, this)
                }

                doneLatch.await()

                finishedLatch.await()
                holder.checkForException()
            }

            enterRetryCounter.get() shouldBe retryAttempts * (count - 1)
            enterCounter.get() shouldBe count

            leaveRetryCounter.get() shouldBe retryAttempts * (count - 1)
            leaveCounter.get() shouldBe count

            logger.debug { "Done" }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
