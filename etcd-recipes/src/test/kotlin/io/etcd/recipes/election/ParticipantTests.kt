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
import com.pambrose.common.util.sleep
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import org.amshove.kluent.shouldBeEqualTo
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

class ParticipantTests : StringSpec() {
    val path = "/election/${javaClass.simpleName}"

    init {
        "!participantTest" {
            val count = 20
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
                                    val pause = 2.seconds
                                    logger.info { "${selector.clientId} elected leader for $pause" }
                                    sleep(pause)

                                    // Wait until participation count has been taken
                                    holdLatch.await()
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
                sleep(3.seconds)
                var participants = LeaderSelector.getParticipants(client, path)
                logger.info { "Found ${participants.size} participants" }
                participants.size shouldBeEqualTo count

                holdLatch.countDown()

                finishedLatch.await()

                sleep(5.seconds)

                participants = LeaderSelector.getParticipants(client, path)
                logger.info { "Found ${participants.size} participants" }
                participants.size shouldBeEqualTo 0

                // Compare participant counts
                logger.info { "participantCounts = $participantCounts" }
                participantCounts.size shouldBeEqualTo count
                participantCounts shouldBeEqualTo (count downTo 1).toList()

                // Compare leader names
                logger.info { "leaderNames = $leaderNames" }
                leaderNames.sorted() shouldBeEqualTo List(count) { "Thread$it" }.sorted()
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
