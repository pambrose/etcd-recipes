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

package io.etcd.recipes.election

import com.pambrose.common.concurrent.thread
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

class ParticipantTests : StringSpec() {
    val path = "/election/${javaClass.simpleName}"

    init {
        "participantTest" {
            val count = 10
            val startedLatch = CountDownLatch(count)
            val finishedLatch = CountDownLatch(count)
            val holdLatch = CountDownLatch(1)
            val participantCounts: MutableList<Int> = synchronizedList(mutableListOf())
            val leaderNames: MutableList<String> = synchronizedList(mutableListOf())

            connectToEtcd(urls) { client ->
                blockingThreads(count) {
                    thread(finishedLatch) {
                        withLeaderSelector(
                            client,
                            path,
                            object : LeaderSelectorListenerAdapter() {
                                override fun takeLeadership(selector: LeaderSelector) {
                                    logger.info { "${selector.clientId} elected leader" }

                                    // Wait until participation count has been taken
                                    holdLatch.await()
                                    // Each leader's participant lease only expires after its
                                    // selector closes. Wait for previous leaders' leases to
                                    // expire so we observe a strictly decreasing count.
                                    val expected = count - participantCounts.size
                                    pollUntil(15.seconds) {
                                        LeaderSelector.getParticipants(client, path).size == expected
                                    } shouldBe true
                                    participantCounts += LeaderSelector.getParticipants(client, path).size
                                    leaderNames += selector.clientId
                                }
                            },
                            clientId = "Thread$it",
                        ) {
                            start()
                            startedLatch.countDown()
                            waitOnLeadershipComplete()
                        }
                    }
                }

                startedLatch.await()

                // Wait for participants to register
                pollUntil(15.seconds) {
                    LeaderSelector.getParticipants(client, path).size == count
                } shouldBe true
                var participants = LeaderSelector.getParticipants(client, path)
                logger.info { "Found ${participants.size} participants" }
                participants.size shouldBe count

                holdLatch.countDown()

                finishedLatch.await()

                // After all leaders complete, etcd should evict every participant lease.
                pollUntil(15.seconds) {
                    LeaderSelector.getParticipants(client, path).isEmpty()
                } shouldBe true
                participants = LeaderSelector.getParticipants(client, path)
                participants.size shouldBe 0

                // Compare participant counts
                logger.info { "participantCounts = $participantCounts" }
                participantCounts.size shouldBe count
                participantCounts shouldBe (count downTo 1).toList()

                // Compare leader names
                logger.info { "leaderNames = $leaderNames" }
                leaderNames.sorted() shouldBe List(count) { "Thread$it" }.sorted()
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
