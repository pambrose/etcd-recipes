/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.examples.election

import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.election.withLeaderSelector
import kotlin.time.Duration.Companion.seconds

fun main() {
  val urls = listOf("http://localhost:2379")
  val electionPath = "/election/single"

  val leadershipAction = { selector: LeaderSelector ->
    println("${selector.clientId} elected leader")
    val pause = 0.seconds //Random.nextInt(1, 3).seconds
    sleep(pause)
    println("${selector.clientId} surrendering after $pause")
  }

  connectToEtcd(urls) { client ->
    withLeaderSelector(client, electionPath, leadershipAction) {
      repeat(100) {
        println("Iteration $it")
        start()
        waitOnLeadershipComplete()
      }
    }

    repeat(5) {
      withLeaderSelector(client, electionPath, leadershipAction) {
        println("Iteration $it")
        start()
        waitOnLeadershipComplete()
      }
    }
  }
}