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

package io.etcd.recipes.election

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.seconds

class SerialLeaderSelectorTests {
    val path = "/election/${javaClass.simpleName}"

    @Test
    fun badArgsTest() {
        invoking { LeaderSelector(urls, "") } shouldThrow IllegalArgumentException::class
        invoking { LeaderSelector(emptyList(), "something") } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun serialElectionTest() {
        val count = 10
        val takeLeadershiptCounter = AtomicInteger(0)
        val relinquishLeadershiptCounter = AtomicInteger(0)

        val leadershipAction = { selector: LeaderSelector ->
            val pause = 3.random.seconds
            logger.info { "${selector.clientId} elected leader for $pause" }
            sleep(pause)
            takeLeadershiptCounter.incrementAndGet()
            Unit
        }

        val relinquishAction = { selector: LeaderSelector ->
            logger.info { "${selector.clientId} relinquished" }
            relinquishLeadershiptCounter.incrementAndGet()
            Unit
        }

        LeaderSelector(urls, path, leadershipAction, relinquishAction)
            .use { selector ->
                repeat(count) {
                    logger.info { "First iteration: $it" }
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
            logger.info { "Second iteration: $it" }
            LeaderSelector(urls, path, leadershipAction, relinquishAction)
                .use { selector ->
                    selector.start()
                    selector.waitOnLeadershipComplete()
                }
        }

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count
    }

    companion object : KLogging()
}