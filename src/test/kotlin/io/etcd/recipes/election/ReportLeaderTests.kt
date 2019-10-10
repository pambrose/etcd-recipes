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
import io.etcd.recipes.common.blockingThreads
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.seconds

class ReportLeaderTests {
    val urls = listOf("http://localhost:2379")
    val path = "/election/${javaClass.simpleName}"

    @Test
    fun reportLeaderTest() {
        val count = 25
        val takeLeadershiptCounter = AtomicInteger(0)
        val relinquishLeadershiptCounter = AtomicInteger(0)

        val executor = Executors.newSingleThreadExecutor()
        LeaderSelector.reportLeader(urls,
                                    path,
                                    object : LeaderListener {
                                        override fun takeLeadership(leaderName: String) {
                                            println("$leaderName elected leader")
                                            takeLeadershiptCounter.incrementAndGet()
                                        }

                                        override fun relinquishLeadership() {
                                            relinquishLeadershiptCounter.incrementAndGet()
                                        }
                                    },
                                    executor)

        sleep(5.seconds)

        blockingThreads(count) {
            LeaderSelector(urls,
                           path,
                           object : LeaderSelectorListenerAdapter() {
                               override fun takeLeadership(selector: LeaderSelector) {
                                   val pause = 2.random.seconds
                                   println("${selector.clientId} elected leader for $pause")
                                   sleep(pause)
                               }
                           },
                           "Thread$it")
                .use { election ->
                    election.start()
                    election.waitOnLeadershipComplete()
                }
        }

        // This requires a pause because reportLeader() needs to get notified (via a watcher) of the change in leadership
        sleep(3.seconds)

        executor.shutdown()

        takeLeadershiptCounter.get() shouldEqual count
        relinquishLeadershiptCounter.get() shouldEqual count
    }

}