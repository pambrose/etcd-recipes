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

package io.etcd.recipes.fault

import io.etcd.jetcd.Client
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.election.LeaderLatchListener
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Leader-latch behavior under real faults: losing the leadership lease steps the latch
 * down and it re-contests; an etcd restart converges leadership again.
 */
class LeaderLatchFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  private fun revokeLeaseOf(
    client: Client,
    keyPath: String,
  ) {
    val leaseId = client.getResponse(keyPath).kvs.first().lease
    (leaseId > 0L) shouldBe true
    client.leaseClient.revoke(leaseId).get()
  }

  init {
    "a latch steps down when its leadership lease is revoked and re-acquires when alone" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val electionPath = "$path/revoke"
        val events = CopyOnWriteArrayList<String>()

        val latch = LeaderLatch(client, electionPath, clientId = "solo", leaseTtlSecs = 2)
        latch.addListener(
          object : LeaderLatchListener {
            override fun isLeader() {
              events += "isLeader"
            }

            override fun notLeader() {
              events += "notLeader"
            }
          },
        )
        latch.start()
        try {
          latch.await(20.seconds) shouldBe true

          revokeLeaseOf(client, "$electionPath/LEADER")

          pollUntil(20.seconds) { !latch.hasLeadership } shouldBe true
          // Alone in the election, it re-contests and wins again
          pollUntil(30.seconds) { latch.hasLeadership } shouldBe true
          events.take(3) shouldBe listOf("isLeader", "notLeader", "isLeader")
        } finally {
          latch.close()
        }
      }
    }
  }
}
