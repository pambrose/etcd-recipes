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

package com.sudothought.etcdrecipes.util

import com.sudothought.common.util.sleep
import com.sudothought.etcdrecipes.election.LeaderListener
import com.sudothought.etcdrecipes.election.LeaderSelector
import java.util.concurrent.Executors
import kotlin.time.MonoClock
import kotlin.time.days

fun main() {
    val url = "http://localhost:2379"
    val electionName = "/election/threaded"
    val executor = Executors.newSingleThreadExecutor()
    val latch =
        LeaderSelector.reportLeader(url,
                                    electionName,
                                    object : LeaderListener {
                                        //val electedClock = MonoClock
                                        val unelectedClock = MonoClock
                                        //var electedTime = electedClock.markNow()
                                        var unelectedTime = unelectedClock.markNow()

                                        override fun takeLeadership(leaderName: String) {
                                            println("$leaderName is now the leader [${unelectedTime.elapsedNow()}]")
                                            //electedClock.markNow()
                                        }

                                        override fun relinquishLeadership() {
                                            //println("No longer the leader [${electedTime.elapsedNow()}]")
                                            unelectedClock.markNow()
                                        }
                                    },
                                    executor)

    sleep(1.days)
    latch.countDown()
    executor.shutdown()
}