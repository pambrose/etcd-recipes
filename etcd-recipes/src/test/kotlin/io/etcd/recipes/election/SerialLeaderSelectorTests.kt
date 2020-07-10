/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.util.random
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import kotlinx.atomicfu.atomic
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import kotlin.time.seconds

class SerialLeaderSelectorTests {
    val path = "/election/${javaClass.simpleName}"

    @Test
    fun badArgsTest() {
        connectToEtcd(urls) { client ->
            invoking { LeaderSelector(client, "") } shouldThrow IllegalArgumentException::class
        }
    }

    @Test
    fun serialElectionTest() {
        val count = 10
        val takeLeadershiptCounter = atomic(0)
        val relinquishLeadershiptCounter = atomic(0)

        val leadershipAction = { selector: LeaderSelector ->
            val pause = 3.random().seconds
            logger.debug { "${selector.clientId} elected leader for $pause" }
            sleep(pause)
            takeLeadershiptCounter.incrementAndGet()
            Unit
        }

        val relinquishAction = { selector: LeaderSelector ->
            logger.debug { "${selector.clientId} relinquished" }
            relinquishLeadershiptCounter.incrementAndGet()
            Unit
        }

        connectToEtcd(urls) { client ->
            withLeaderSelector(client, path, leadershipAction, relinquishAction) {
                repeat(count) {
                    logger.debug { "First iteration: $it" }
                    start()
                    waitOnLeadershipComplete()
                }
            }
        }

        takeLeadershiptCounter.value shouldBeEqualTo count
        relinquishLeadershiptCounter.value shouldBeEqualTo count

        // Reset counters
        takeLeadershiptCounter.value = 0
        relinquishLeadershiptCounter.value = 0

        connectToEtcd(urls) { client ->
            repeat(count) {
                logger.debug { "Second iteration: $it" }
                withLeaderSelector(client, path, leadershipAction, relinquishAction) {
                    start()
                    waitOnLeadershipComplete()
                }
            }
        }

        takeLeadershiptCounter.value shouldBeEqualTo count
        relinquishLeadershiptCounter.value shouldBeEqualTo count
    }

    companion object : KLogging()
}