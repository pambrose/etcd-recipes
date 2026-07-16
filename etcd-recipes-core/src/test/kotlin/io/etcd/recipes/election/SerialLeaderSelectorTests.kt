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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch

class SerialLeaderSelectorTests : StringSpec() {
    val path = "/election/${javaClass.simpleName}"

    init {
        "badArgsTest" {
            connectToEtcd(urls) { client ->
                shouldThrow<IllegalArgumentException> { LeaderSelector(client, "") }
            }
        }

        "serialElectionTest" {
            val count = 5
            val takeLeadershiptCounter = AtomicInt(0)
            val relinquishLeadershiptCounter = AtomicInt(0)

            val leadershipAction = { selector: LeaderSelector ->
                logger.info { "${selector.clientId} elected leader" }
                takeLeadershiptCounter.incrementAndFetch()
                Unit
            }

            val relinquishAction = { selector: LeaderSelector ->
                logger.info { "${selector.clientId} relinquished" }
                relinquishLeadershiptCounter.incrementAndFetch()
                Unit
            }

            connectToEtcd(urls) { client ->
                withLeaderSelector(client, path, leadershipAction, relinquishAction) {
                    repeat(count) {
                        logger.info { "First iteration: $it" }
                        start()
                        waitOnLeadershipComplete()
                    }
                }
            }

            takeLeadershiptCounter.load() shouldBe count
            relinquishLeadershiptCounter.load() shouldBe count

            // Reset counters
            takeLeadershiptCounter.store(0)
            relinquishLeadershiptCounter.store(0)

            connectToEtcd(urls) { client ->
                repeat(count) {
                    logger.info { "Second iteration: $it" }
                    withLeaderSelector(client, path, leadershipAction, relinquishAction) {
                        start()
                        waitOnLeadershipComplete()
                    }
                }
            }

            takeLeadershiptCounter.load() shouldBe count
            relinquishLeadershiptCounter.load() shouldBe count
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
