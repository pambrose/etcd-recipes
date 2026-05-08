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

import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Collections.synchronizedList
import java.util.concurrent.atomic.AtomicInteger

class ThreadedLeaderSelectorTests : StringSpec() {
    val path = "/election/${javaClass.simpleName}"
    val count = 10

    init {
        "threadedElection1Test" {
            val takeLeadershiptCounter = AtomicInteger(0)
            val relinquishLeadershiptCounter = AtomicInteger(0)

            blockingThreads(count) {
                val takeAction =
                    { selector: LeaderSelector ->
                        logger.info { "${selector.clientId} elected leader" }
                        takeLeadershiptCounter.incrementAndGet()
                        Unit
                    }

                val relinquishAction =
                    { selector: LeaderSelector ->
                        relinquishLeadershiptCounter.incrementAndGet()
                        logger.info { "${selector.clientId} relinquished leadership" }
                    }

                connectToEtcd(urls) { client ->
                    withLeaderSelector(client, path, takeAction, relinquishAction, clientId = "Thread$it") {
                        start()
                        waitOnLeadershipComplete()
                    }
                }
            }

            takeLeadershiptCounter.get() shouldBe count
            relinquishLeadershiptCounter.get() shouldBe count
        }

        "threadedElection2Test" {
            val takeLeadershiptCounter = AtomicInteger(0)
            val relinquishLeadershiptCounter = AtomicInteger(0)
            val electionList: MutableList<LeaderSelector> = synchronizedList(mutableListOf())

            val takeAction =
                { selector: LeaderSelector ->
                    logger.info { "${selector.clientId} elected leader" }
                    takeLeadershiptCounter.incrementAndGet()
                    Unit
                }

            val relinquishAction =
                { selector: LeaderSelector ->
                    relinquishLeadershiptCounter.incrementAndGet()
                    logger.info { "${selector.clientId} relinquished leadership" }
                }

            connectToEtcd(urls) { client ->
                blockingThreads(count) {
                    logger.info { "Creating Thread$it" }

                    val election = LeaderSelector(client, path, takeAction, relinquishAction, clientId = "Thread$it")
                    electionList += election
                    election.start()
                }

                logger.info { "Size = ${electionList.size}" }

                electionList
                    .onEach { it.waitOnLeadershipComplete() }
                    .forEach { it.close() }
            }

            takeLeadershiptCounter.get() shouldBe count
            relinquishLeadershiptCounter.get() shouldBe count
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
