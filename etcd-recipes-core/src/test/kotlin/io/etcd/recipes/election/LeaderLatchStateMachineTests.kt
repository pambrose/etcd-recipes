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

import io.etcd.jetcd.Client
import io.etcd.recipes.common.pollUntil
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlin.time.Duration.Companion.seconds

/**
 * Pure notify/state-machine behavior of [LeaderLatch] with no etcd: the leadership
 * transitions flip `hasLeadership` synchronously, dispatch listeners in order, and
 * record a throwing listener's failure without breaking the machine.
 */
class LeaderLatchStateMachineTests : StringSpec() {
  private fun newLatch() = LeaderLatch(mockk<Client>(relaxed = true), "/election/state", clientId = "test")

  init {
    "onBecameLeader flips hasLeadership synchronously and fires isLeader once" {
      val latch = newLatch()
      val listener = mockk<LeaderLatchListener>(relaxed = true)
      latch.addListener(listener)

      latch.onBecameLeader()
      latch.hasLeadership shouldBe true
      verify(timeout = 5_000, exactly = 1) { listener.isLeader() }
      verify(exactly = 0) { listener.notLeader() }
    }

    "onLostLeadership clears hasLeadership and fires notLeader" {
      val latch = newLatch()
      val listener = mockk<LeaderLatchListener>(relaxed = true)
      latch.addListener(listener)

      latch.onBecameLeader()
      latch.onLostLeadership()
      latch.hasLeadership shouldBe false
      verify(timeout = 5_000, exactly = 1) { listener.notLeader() }
    }

    "a throwing listener is caught and recorded in exceptions" {
      val latch = newLatch()
      latch.addListener(
        object : LeaderLatchListener {
          override fun isLeader(): Unit = throw IllegalStateException("listener boom")
        },
      )

      latch.onBecameLeader()
      pollUntil(5.seconds) { latch.hasExceptions } shouldBe true
      latch.exceptions.any { it.message == "listener boom" } shouldBe true
      // The machine itself is unaffected — state still reflects leadership
      latch.hasLeadership shouldBe true
    }
  }
}
