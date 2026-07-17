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
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.election.LeaderLatchListener
import io.etcd.recipes.election.withLeaderLatch
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun basicLatch(client: Client) {
  // --8<-- [start:basic]
  LeaderLatch(client, "/election/reports", clientId = "reporter-1").use { latch ->
    latch.start()
    // Blocks until this node holds leadership; it then holds it until close().
    latch.await()
    logger.info { "${latch.clientId} is the leader" }
    // Leaving use{} closes the latch, releases candidacy, and a successor takes over.
  }
  // --8<-- [end:basic]
}

fun timedAwaitLatch(client: Client) {
  // --8<-- [start:timed-await]
  LeaderLatch(client, "/election/reports", clientId = "reporter-1").use { latch ->
    latch.start()
    // Prefer the timed form: a parked no-arg await() is released only by an
    // interrupt or a timeout — close() does NOT unblock it.
    if (latch.await(30.seconds)) {
      logger.info { "Leading" }
    } else {
      logger.info { "Someone else is leading; carrying on as a follower" }
    }
  }
  // --8<-- [end:timed-await]
}

fun latchHasLeadership(client: Client) {
  // --8<-- [start:has-leadership]
  LeaderLatch(client, "/election/reports").use { latch ->
    latch.start()
    // Advisory, and never a substitute for the work itself being safe: the lease
    // can expire between this read and the next line.
    if (latch.hasLeadership) logger.info { "Running the leader-only sweep" }
  }
  // --8<-- [end:has-leadership]
}

fun latchWithListener(client: Client) {
  // --8<-- [start:listener]
  val listener =
    object : LeaderLatchListener {
      override fun isLeader() {
        logger.info { "Gained leadership; starting the scheduler" }
      }

      override fun notLeader() {
        // Fires on close() and on a step-down after lease loss. Stop leader-only work.
        logger.info { "Lost leadership; stopping the scheduler" }
      }
    }

  LeaderLatch(client, "/election/reports").use { latch ->
    latch.addListener(listener)
    latch.start()
    latch.await(30.seconds)
  }
  // --8<-- [end:listener]
}

fun scopedLatch(client: Client) {
  // --8<-- [start:scoped]
  // Unlike withLeaderSelector, withLeaderLatch starts the latch for you.
  withLeaderLatch(client, "/election/reports", clientId = "reporter-1") {
    if (await(30.seconds)) logger.info { "$clientId is the leader" }
  }
  // --8<-- [end:scoped]
}

fun latchLeaseLoss(client: Client) {
  // --8<-- [start:lease-loss]
  LeaderLatch(
    client = client,
    electionPath = "/election/reports",
    leaseTtlSecs = 5L,
    clientId = "reporter-1",
    // The default. On lease loss the latch steps down, fires notLeader(), and
    // re-contests with a fresh term rather than assuming it still leads.
    interruptOnLeaseLoss = true,
  ).use { latch ->
    latch.addListener(
      object : LeaderLatchListener {
        override fun notLeader() = logger.warn { "Stepped down; another node may already lead" }
      },
    )
    latch.start()
    latch.await(30.seconds)
  }
  // --8<-- [end:lease-loss]
}
