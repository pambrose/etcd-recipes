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

package io.etcd.recipes.examples.election

import com.sudothought.common.concurrent.countDown
import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.election.LeaderSelector.Companion.getParticipants
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val electionPath = "/election/threaded"
    val count = 5
    val latch = CountDownLatch(count)

    repeat(count) {
        thread {
            latch.countDown {
                val takeLeadershipAction =
                    { selector: LeaderSelector ->
                        println("${selector.clientId} elected leader")
                        val pause = 3.random.seconds
                        sleep(pause)
                        println("${selector.clientId} surrendering after $pause")
                    }

                val relinquishLeadershipAction =
                    { selector: LeaderSelector ->
                        println("${selector.clientId} relinquished leadership")
                    }

                LeaderSelector(urls,
                               electionPath,
                               takeLeadershipAction,
                               relinquishLeadershipAction,
                               clientId = "Thread$it")
                    .use { election ->
                        election.start()
                        election.waitOnLeadershipComplete()
                    }
            }
        }
    }

    while (latch.count > 0) {
        println("Participants: ${getParticipants(urls, electionPath)}")
        sleep(1.seconds)
    }

    latch.await()
}