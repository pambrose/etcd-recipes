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

package com.sudothought.etcdrecipes.election

import com.sudothought.common.util.sleep
import com.sudothought.etcdrecipes.counter.DistributedAtomicLong
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.seconds

class LeaderSelectorTest {
    val urls = listOf("http://localhost:2379")
    val path = "/election/LeaderSelectorTest"
    val count = 5

    @BeforeEach
    fun deleteElection() = DistributedAtomicLong.delete(urls, path)

    @Test
    fun countdownElectionTest() {
        val latch = CountDownLatch(count)
        val takeLeadershiptCounter = AtomicInteger(0)
        val relinquishLeadershiptCounter = AtomicInteger(0)

        repeat(count) {
            thread {
                val takeLeadershipAction =
                    { selector: LeaderSelector ->
                        println("${selector.clientId} elected leader")
                        takeLeadershiptCounter.incrementAndGet()
                        val pause = Random.nextInt(1, 3).seconds
                        sleep(pause)
                        println("${selector.clientId} surrendering after $pause")
                    }

                val relinquishLeadershipAction =
                    { selector: LeaderSelector ->
                        relinquishLeadershiptCounter.incrementAndGet()
                        println("${selector.clientId} relinquished leadership")
                    }

                LeaderSelector(urls, path, takeLeadershipAction, relinquishLeadershipAction, "Thread$it")
                    .use { election ->
                        election.start()
                        election.waitOnLeadershipComplete()
                        sleep(2.seconds)
                        latch.countDown()
                    }
            }
        }

        latch.await()

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count
    }

    @Test
    fun reportLeaderTest() {
        val latch = CountDownLatch(count)
        val takeLeadershiptCounter = AtomicInteger(0)
        val relinquishLeadershiptCounter = AtomicInteger(0)

        val executor = Executors.newSingleThreadExecutor()
        LeaderSelector.reportLeader(urls,
                                    path,
                                    object : LeaderListener {
                                        override fun takeLeadership(leaderName: String) {
                                            takeLeadershiptCounter.incrementAndGet()
                                            val pause = Random.nextInt(1, 3).seconds
                                            sleep(pause)
                                        }

                                        override fun relinquishLeadership() {
                                            relinquishLeadershiptCounter.incrementAndGet()
                                        }
                                    },
                                    executor)
        repeat(count) {
            thread {
                LeaderSelector(urls, path, {}, {}, "Thread$it")
                    .use { election ->
                        election.start()
                        election.waitOnLeadershipComplete()
                        sleep(2.seconds)
                        latch.countDown()
                    }
            }
        }

        latch.await()

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count

        executor.shutdown()
    }
}