/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package org.athenian.etcdrecipes.election

import com.sudothought.common.util.sleep
import org.athenian.etcdrecipes.election.LeaderSelector.Static.getParticipants
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val electionName = "/election/threaded"
    val count = 50
    val latch = CountDownLatch(count)

    LeaderSelector.reset(url, electionName)

    val leadershipAction =
        { selector: LeaderSelector ->
            println("${selector.clientId} elected leader")
            val pause = Random.nextInt(1, 3).seconds
            sleep(pause)
            println("${selector.clientId} surrendering after $pause")
        }

    repeat(count) {
        thread {
            LeaderSelector(url, electionName, leadershipAction, "Thread$it")
                .use { election ->
                    election.start()
                    election.waitOnLeadershipComplete()
                }
            latch.countDown()
        }
    }

    while (latch.count > 0) {
        println("Participants: ${getParticipants(url, electionName)}")
        sleep(1.seconds)
    }

    latch.await()
}