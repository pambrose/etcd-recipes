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
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

class ParticipantTest {
    val urls = listOf("http://localhost:2379")
    val path = "/election/${javaClass.simpleName}"

    @Test
    fun reportLeaderTest() {
        val count = 20
        val startedLatch = CountDownLatch(count)
        val finishedLatch = CountDownLatch(count)
        val holdLatch = CountDownLatch(1)
        val participantCounts = mutableListOf<Int>()
        val leaderNames = mutableListOf<String>()


        repeat(count) {
            thread {
                LeaderSelector(urls,
                               path,
                               object : LeaderSelectorListenerAdapter() {
                                   override fun takeLeadership(selector: LeaderSelector) {
                                       val pause = 2.seconds
                                       println("${selector.clientId} elected leader for $pause")
                                       sleep(pause)

                                       // Wait until participation count has been taken
                                       holdLatch.await()
                                       participantCounts += LeaderSelector.getParticipants(urls, path).size
                                       leaderNames += selector.clientId
                                   }
                               },
                               "Thread$it")
                    .use { election ->
                        election.start()
                        startedLatch.countDown()
                        election.waitOnLeadershipComplete()
                        finishedLatch.countDown()
                    }
            }
        }

        startedLatch.await()

        // Wait for participants to register
        sleep(3.seconds)

        var particpants = LeaderSelector.getParticipants(urls, path)
        println("Found ${particpants.size} participants")
        particpants.size shouldEqual count

        holdLatch.countDown()

        finishedLatch.await()

        sleep(3.seconds)
        particpants = LeaderSelector.getParticipants(urls, path)
        println("Found ${particpants.size} participants")
        particpants.size shouldEqual 0

        // Compare participant counts
        println("participantCounts = $participantCounts")
        participantCounts.size shouldEqual count
        participantCounts shouldEqual (count downTo 1).toList()

        // Compare leader names
        println("leaderNames = $leaderNames")
        leaderNames.sorted() shouldEqual List(count) { "Thread$it" }.sorted()
    }
}