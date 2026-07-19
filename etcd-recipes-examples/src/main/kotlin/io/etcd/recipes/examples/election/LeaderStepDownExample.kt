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

package io.etcd.recipes.examples.election

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.election.LeaderSelector
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Demonstrates leader step-down on lease loss: the elected leader holds leadership
 * until its lease disappears (kill etcd for longer than the TTL, or revoke the
 * LEADER key's lease with etcdctl), at which point isLeader turns false, the
 * takeLeadership block is released, and relinquishLeadership runs — no split brain.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val electionPath = "/election/step-down-example"

  connectToEtcd(urls) { client ->
    LeaderSelector(
      client,
      electionPath,
      takeLeadershipBlock = { selector ->
        logger.info { "Took leadership; holding until shutdown or lease loss" }
        selector.waitUntilFinished()
        logger.info { "takeLeadership released (isLeader=${selector.isLeader})" }
      },
      relinquishLeadershipBlock = { _ ->
        logger.info { "Relinquished leadership" }
      },
    ).use { selector ->
      selector.addConnectionStateListener { new, prev -> logger.info { "Connection state: $prev -> $new" } }
      selector.start()
      selector.waitOnLeadershipComplete()
      logger.info { "Election over; exceptions recorded: ${selector.exceptions.map { it.message }}" }
    }
  }
}
