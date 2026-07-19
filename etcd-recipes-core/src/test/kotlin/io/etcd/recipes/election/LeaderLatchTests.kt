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
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Leader-latch semantics: hold-until-close leadership, mutual exclusion across N
 * competing latches, hand-off on close, listener notifications, and — the key
 * property — interoperation with `LeaderSelector` in the same election.
 */
class LeaderLatchTests : StringSpec() {
  private val path = "/election/${javaClass.simpleName}"

  init {
    beforeSpec { urls }

    "a lone latch acquires leadership and releases it on close" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val latch = LeaderLatch(client, "$path/lone", clientId = "solo").start()
        try {
          latch.await(20.seconds) shouldBe true
          latch.hasLeadership shouldBe true
          // Participant advertisement is async, so poll rather than reading it point-in-time.
          pollUntil(10.seconds) {
            runCatching {
              LeaderSelector.getParticipants(client, "$path/lone").single { it.isLeader }.clientId == "solo"
            }.getOrDefault(false)
          } shouldBe true
        } finally {
          latch.close()
        }
        latch.hasLeadership shouldBe false
        latch.hasExceptions shouldBe false
      }
    }

    "await(timeout) returns false while another latch holds leadership" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val holder = LeaderLatch(client, "$path/contended", clientId = "holder").start()
        try {
          holder.await(20.seconds) shouldBe true
          val contender = LeaderLatch(client, "$path/contended", clientId = "contender").start()
          try {
            contender.await(2.seconds) shouldBe false
            contender.hasLeadership shouldBe false
          } finally {
            contender.close()
          }
        } finally {
          holder.close()
        }
      }
    }

    "exactly one of N competing latches holds leadership at a time" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val count = 5
        val latches = (1..count).map { LeaderLatch(client, "$path/exclusive", clientId = "node$it").start() }
        try {
          pollUntil(20.seconds) { latches.count { it.hasLeadership } == 1 } shouldBe true
          repeat(10) {
            latches.count { it.hasLeadership } shouldBe 1
            Thread.sleep(50)
          }
        } finally {
          latches.forEach { it.close() }
        }
      }
    }

    "every latch eventually leads as predecessors close" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val count = 4
        val latches =
          (1..count).associate { "node$it" to LeaderLatch(client, "$path/handoff", clientId = "node$it").start() }
        try {
          val ledAtLeastOnce = mutableSetOf<String>()
          repeat(count) {
            val leader =
              pollUntil(20.seconds) { latches.values.any { l -> l.hasLeadership } }
                .let { latches.entries.first { (_, l) -> l.hasLeadership } }
            ledAtLeastOnce += leader.key
            leader.value.close() // hand off to a successor
          }
          ledAtLeastOnce shouldContainExactlyInAnyOrder latches.keys
        } finally {
          latches.values.forEach { it.close() }
        }
      }
    }

    "listeners fire isLeader before notLeader" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val events = CopyOnWriteArrayList<String>()
        val gotLeadership = BooleanMonitor(false)
        val latch = LeaderLatch(client, "$path/listener", clientId = "solo")
        latch.addListener(
          object : LeaderLatchListener {
            override fun isLeader() {
              events += "isLeader"
              gotLeadership.set(true)
            }

            override fun notLeader() {
              events += "notLeader"
            }
          },
        )
        latch.start()
        gotLeadership.waitUntilTrue(20.seconds) shouldBe true
        latch.close()
        pollUntil(10.seconds) { events.contains("notLeader") } shouldBe true
        events.toList() shouldBe ["isLeader", "notLeader"]
      }
    }

    "a latch and a selector in the same election mutually exclude and hand off" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val electionPath = "$path/interop"
        val selectorLed = BooleanMonitor(false)
        val release = BooleanMonitor(false)

        val latch = LeaderLatch(client, electionPath, clientId = "the-latch").start()
        val selector =
          LeaderSelector(
            client,
            electionPath,
            takeLeadershipBlock = {
              selectorLed.set(true)
              release.waitUntilTrue()
            },
            clientId = "the-selector",
          ).start()
        try {
          // One of them leads; whichever it is, the other must not
          pollUntil(20.seconds) { latch.hasLeadership || selectorLed.get() } shouldBe true
          repeat(10) {
            (latch.hasLeadership && selectorLed.get()) shouldBe false
            Thread.sleep(50)
          }

          if (latch.hasLeadership) {
            // Latch leads → closing it hands leadership to the selector
            latch.close()
            selectorLed.waitUntilTrue(20.seconds) shouldBe true
            release.set(true)
          } else {
            // Selector leads → releasing it hands leadership to the latch
            release.set(true)
            latch.await(20.seconds) shouldBe true
          }
        } finally {
          release.set(true)
          selector.close()
          latch.close()
        }
      }
    }

    "closing a latch that never led terminates promptly without exceptions" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val electionPath = "$path/never-led"
        val holder = LeaderLatch(client, electionPath, clientId = "holder").start()
        try {
          holder.await(20.seconds) shouldBe true
          val waiter = LeaderLatch(client, electionPath, clientId = "waiter").start()
          waiter.hasLeadership shouldBe false
          waiter.close() // parked pre-leadership; must return promptly
          waiter.hasLeadership shouldBe false
          waiter.hasExceptions shouldBe false
        } finally {
          holder.close()
        }
      }
    }
  }
}
