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

package io.etcd.recipes.election

import com.pambrose.common.concurrent.BooleanMonitor
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Drives lease loss for real by revoking a recipe's lease out-of-band: etcd exposes
 * every key's lease id ([io.etcd.jetcd.KeyValue.getLease]), and revoking it kills
 * the keep-alive stream with NOT_FOUND — exactly what a partition longer than the
 * TTL produces, without needing a container pause.
 *
 * Split-brain regression: before step-down, a leader whose lease vanished kept
 * reporting isLeader=true while another candidate took over.
 */
class LeaderStepDownTests : StringSpec() {
  private val path = "/election/${javaClass.simpleName}"

  private fun revokeLeaseOf(
    client: io.etcd.jetcd.Client,
    keyPath: String,
  ) {
    val leaseId = client.getResponse(keyPath).kvs.first().lease
    (leaseId > 0L) shouldBe true
    client.leaseClient.revoke(leaseId).get()
  }

  init {
    "leader steps down when its leadership lease is lost" {
      connectToEtcd(urls) { client ->
        val electionPath = "$path/stepdown"
        val relinquishCount = AtomicInteger(0)
        val bLeaderships = AtomicInteger(0)
        val aElected = BooleanMonitor(false)

        val selectorA =
          LeaderSelector(
            client,
            electionPath,
            takeLeadershipBlock = { selector ->
              aElected.set(true)
              // Parks on the finished monitor: step-down must release it.
              selector.waitUntilFinished()
            },
            relinquishLeadershipBlock = { relinquishCount.incrementAndGet() },
          )
        val selectorB =
          LeaderSelector(
            client,
            electionPath,
            takeLeadershipBlock = { bLeaderships.incrementAndGet() },
          )

        selectorA.use { a ->
          selectorB.use { b ->
            a.start()
            aElected.waitUntilTrue(20.seconds) shouldBe true
            b.start()

            // Simulate lease expiry: revoke the leadership lease out-of-band
            revokeLeaseOf(client, "$electionPath/LEADER")

            pollUntil(20.seconds) { !a.isLeader } shouldBe true
            a.waitOnLeadershipComplete(20.seconds) shouldBe true
            pollUntil(10.seconds) { relinquishCount.get() == 1 } shouldBe true

            // The other candidate takes over (no split brain: A already stepped down)
            pollUntil(20.seconds) { bLeaderships.get() == 1 } shouldBe true
            b.waitOnLeadershipComplete(20.seconds) shouldBe true
          }
        }
      }
    }

    "step-down interrupts a takeLeadership parked in its own code" {
      connectToEtcd(urls) { client ->
        val electionPath = "$path/interrupt"
        val aElected = BooleanMonitor(false)
        val interrupted = BooleanMonitor(false)

        LeaderSelector(
          client,
          electionPath,
          takeLeadershipBlock = { _ ->
            aElected.set(true)
            try {
              Thread.sleep(300_000) // user code parked where only an interrupt reaches it
            } catch (e: InterruptedException) {
              interrupted.set(true)
            }
          },
        ).use { a ->
          a.start()
          aElected.waitUntilTrue(20.seconds) shouldBe true

          revokeLeaseOf(client, "$electionPath/LEADER")

          pollUntil(20.seconds) { !a.isLeader } shouldBe true
          a.waitOnLeadershipComplete(20.seconds) shouldBe true
          interrupted.waitUntilTrue(10.seconds) shouldBe true
        }
      }
    }

    "participation key heals after its lease is lost" {
      connectToEtcd(urls) { client ->
        val electionPath = "$path/participation"
        val holdLeadership = BooleanMonitor(false)

        LeaderSelector(
          client,
          electionPath,
          takeLeadershipBlock = { _ -> holdLeadership.waitUntilTrue() },
          clientId = "healing-participant",
        ).use { selector ->
          selector.start()
          val participationPath = "$electionPath/participants/healing-participant"
          pollUntil(20.seconds) { client.isKeyPresent(participationPath) } shouldBe true

          // Simulate lease expiry for the participation key only
          revokeLeaseOf(client, participationPath)
          pollUntil(10.seconds) { !client.isKeyPresent(participationPath) } shouldBe true

          // The self-healing participation lease re-registers the key
          pollUntil(20.seconds) { client.isKeyPresent(participationPath) } shouldBe true

          holdLeadership.set(true)
        }
      }
    }
  }
}
