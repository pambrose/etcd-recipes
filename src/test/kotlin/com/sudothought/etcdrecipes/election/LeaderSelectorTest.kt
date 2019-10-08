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

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.seconds

class LeaderSelectorTest {
    val urls = listOf("http://localhost:2379")
    val path = "/election/${javaClass.simpleName}"

    @Test
    fun serialElectionTest() {
        val count = 10
        val takeLeadershiptCounter = AtomicInteger(0)
        val relinquishLeadershiptCounter = AtomicInteger(0)

        val leadershipAction = { selector: LeaderSelector ->
            val pause = 3.random.seconds
            println("${selector.clientId} elected leader for $pause")
            sleep(pause)
            takeLeadershiptCounter.incrementAndGet()
            Unit
        }

        val relinquishAction = { selector: LeaderSelector ->
            println("${selector.clientId} relinquished")
            relinquishLeadershiptCounter.incrementAndGet()
            Unit
        }

        LeaderSelector(urls, path, leadershipAction, relinquishAction)
            .use { selector ->
                repeat(count) {
                    println("First iteration: $it")
                    selector.start()
                    selector.waitOnLeadershipComplete()
                }
            }

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count

        // Reset counters
        takeLeadershiptCounter.set(0)
        relinquishLeadershiptCounter.set(0)

        repeat(count) {
            println("Second iteration: $it")
            LeaderSelector(urls, path, leadershipAction, relinquishAction)
                .use { selector ->
                    selector.start()
                    selector.waitOnLeadershipComplete()
                }
        }

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count
    }

    @Test
    fun threadedElection1Test() {
        val count = 10
        val latch = CountDownLatch(count)
        val takeLeadershiptCounter = AtomicInteger(0)
        val relinquishLeadershiptCounter = AtomicInteger(0)

        repeat(count) {
            thread {
                val takeLeadershipAction =
                    { selector: LeaderSelector ->
                        val pause = 3.random.seconds
                        println("${selector.clientId} elected leader for $pause")
                        takeLeadershiptCounter.incrementAndGet()
                        sleep(pause)
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
                        latch.countDown()
                    }
            }
        }

        latch.await()

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count
    }

    @Test
    fun threadedElection2Test() {
        val count = 10
        val latch = CountDownLatch(count)
        val takeLeadershiptCounter = AtomicInteger(0)
        val relinquishLeadershiptCounter = AtomicInteger(0)
        val electionList = mutableListOf<LeaderSelector>()

        repeat(count) {
            thread {
                val takeLeadershipAction =
                    { selector: LeaderSelector ->
                        val pause = 3.random.seconds
                        println("${selector.clientId} elected leader for $pause")
                        takeLeadershiptCounter.incrementAndGet()
                        sleep(pause)
                    }

                val relinquishLeadershipAction =
                    { selector: LeaderSelector ->
                        relinquishLeadershiptCounter.incrementAndGet()
                        println("${selector.clientId} relinquished leadership")
                    }

                val election = LeaderSelector(urls, path, takeLeadershipAction, relinquishLeadershipAction, "Thread$it")
                electionList += election
                election.start()
                latch.countDown()
            }
        }

        latch.await()

        sleep(5.seconds)

        println("Size = ${electionList.size}")
        electionList
            .onEach { it.waitOnLeadershipComplete() }
            .forEach { it.close() }

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count
    }
}