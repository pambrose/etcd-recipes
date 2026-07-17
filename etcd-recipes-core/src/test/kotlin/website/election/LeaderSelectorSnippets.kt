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

package website.election

import io.etcd.jetcd.Client
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.election.LeaderSelectorListener
import io.etcd.recipes.election.LeaderSelectorListenerAdapter
import io.etcd.recipes.election.Participant
import io.etcd.recipes.election.withLeaderSelector
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun basicSelector(client: Client) {
  // --8<-- [start:basic]
  LeaderSelector(
    client,
    "/election/reports",
    takeLeadershipBlock = { selector ->
      // Exactly one client across the cluster is inside this block at a time.
      // Leadership lasts exactly as long as this block runs.
      logger.info { "${selector.clientId} is the leader" }
    },
    relinquishLeadershipBlock = { selector ->
      logger.info { "${selector.clientId} is no longer the leader" }
    },
  ).use { selector ->
    selector.start()
    // Blocks until this node's term is over.
    selector.waitOnLeadershipComplete()
  }
  // --8<-- [end:basic]
}

fun holdSelector(client: Client) {
  // --8<-- [start:hold]
  LeaderSelector(
    client,
    "/election/reports",
    takeLeadershipBlock = { selector ->
      // Returning ends the term, so a node that means to lead "until shutdown"
      // parks here. waitUntilFinished takes no instance monitor, so a close()
      // from another thread releases it.
      selector.waitUntilFinished()
      // Also reached on step-down: re-check before doing anything leader-only.
      if (!selector.isLeader) logger.warn { "Term ended without a clean shutdown" }
    },
  ).use { selector ->
    selector.start()
    selector.waitOnLeadershipComplete()
  }
  // --8<-- [end:hold]
}

fun selectorWithListener(client: Client) {
  // --8<-- [start:listener]
  val listener =
    object : LeaderSelectorListener {
      override fun takeLeadership(selector: LeaderSelector) {
        logger.info { "${selector.clientId} took leadership" }
      }

      override fun relinquishLeadership(selector: LeaderSelector) {
        logger.info { "${selector.clientId} relinquished leadership" }
      }
    }

  LeaderSelector(client, "/election/reports", listener).use { selector ->
    selector.start()
    selector.waitOnLeadershipComplete()
  }
  // --8<-- [end:listener]
}

fun selectorWithAdapter(client: Client) {
  // --8<-- [start:adapter]
  // The adapter no-ops both callbacks, so override only the one you care about.
  val listener =
    object : LeaderSelectorListenerAdapter() {
      override fun takeLeadership(selector: LeaderSelector) {
        logger.info { "${selector.clientId} took leadership" }
      }
    }

  LeaderSelector(client, "/election/reports", listener).use { selector ->
    selector.start()
    selector.waitOnLeadershipComplete()
  }
  // --8<-- [end:adapter]
}

fun scopedSelector(client: Client) {
  // --8<-- [start:scoped]
  // withLeaderSelector closes the selector on exit, but does not start it —
  // the receiver decides when to run for election.
  withLeaderSelector(
    client,
    "/election/reports",
    takeLeadershipBlock = { selector -> logger.info { "${selector.clientId} is the leader" } },
  ) {
    start()
    waitOnLeadershipComplete()
  }
  // --8<-- [end:scoped]
}

fun reuseSelector(client: Client) {
  // --8<-- [start:reuse]
  // One instance, many terms: start() may be called again once the previous term
  // has completed, so a long-lived node keeps re-running for election.
  LeaderSelector(
    client,
    "/election/reports",
    takeLeadershipBlock = { selector -> logger.info { "${selector.clientId} is the leader" } },
    clientId = "reporter-1",
  ).use { selector ->
    repeat(3) {
      selector.start()
      selector.waitOnLeadershipComplete()
    }
  }
  // --8<-- [end:reuse]
}

fun selectorParticipants(client: Client) {
  // --8<-- [start:participants]
  // Advisory snapshot of every candidate registered at the election path, and
  // which of them held the leader key at read time.
  val participants: List<Participant> = LeaderSelector.getParticipants(client, "/election/reports")
  participants.forEach { participant ->
    logger.info { "${participant.clientId} isLeader=${participant.isLeader}" }
  }
  // --8<-- [end:participants]
}

fun selectorLeaseLoss(client: Client) {
  // --8<-- [start:lease-loss]
  LeaderSelector(
    client = client,
    electionPath = "/election/reports",
    takeLeadershipBlock = { selector ->
      selector.waitUntilFinished()
      // A lost lease means etcd already deleted the leader key: another node may
      // lead by now, so the recipe steps down instead of reclaiming it.
      if (!selector.isLeader) logger.warn { "Stepped down; do not touch leader-only state" }
    },
    relinquishLeadershipBlock = { logger.info { "Cleaning up leader-only resources" } },
    leaseTtlSecs = 5L,
    // The default: interrupt the takeLeadership thread the moment the lease is gone.
    interruptOnLeaseLoss = true,
  ).use { selector ->
    selector.addConnectionStateListener { new, prev -> logger.info { "Connection state: $prev -> $new" } }
    selector.start()
    selector.waitOnLeadershipComplete()
    selector.exceptions.forEach { logger.warn(it) { "Background failure" } }
  }
  // --8<-- [end:lease-loss]
}
