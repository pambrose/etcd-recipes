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
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Non-candidate election observation: current-leader snapshot, hand-off tracking, and
 * listener take/relinquish notifications. The blocking/Java counterpart to the
 * coroutine `leadershipAsFlow`.
 */
class LeaderObserverTests : StringSpec() {
  private val path = "/election/${javaClass.simpleName}"

  init {
    beforeSpec { urls }

    "currentLeader is null on a vacant election" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        LeaderObserver(client, "$path/vacant").use { observer ->
          observer.start()
          observer.currentLeader shouldBe null
        }
      }
    }

    "observer seeds currentLeader from an already-elected leader on start" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val electionPath = "$path/seed"
        val release = BooleanMonitor(false)
        val led = BooleanMonitor(false)
        val latch = LeaderLatch(client, electionPath, clientId = "sitting-leader").start()
        try {
          latch.await(20.seconds) shouldBe true
          LeaderObserver(client, electionPath).use { observer ->
            observer.start()
            observer.currentLeader shouldBe "sitting-leader"
          }
        } finally {
          release.set(true)
          led.set(true)
          latch.close()
        }
      }
    }

    "currentLeader and listeners track a leadership hand-off" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val electionPath = "$path/handoff"
        val takes = CopyOnWriteArrayList<String>()
        val relinquishes = BooleanMonitor(false)

        LeaderObserver(client, electionPath).use { observer ->
          observer.addListener(
            object : LeaderListener {
              override fun takeLeadership(leaderName: String) {
                takes += leaderName
              }

              override fun relinquishLeadership() {
                relinquishes.set(true)
              }
            },
          )
          observer.start()

          val first = LeaderLatch(client, electionPath, clientId = "first").start()
          first.await(20.seconds) shouldBe true
          pollUntil(15.seconds) { observer.currentLeader == "first" } shouldBe true

          val second = LeaderLatch(client, electionPath, clientId = "second").start()
          first.close() // hand off first -> second
          pollUntil(20.seconds) { observer.currentLeader == "second" } shouldBe true
          second.close()

          takes.contains("first") shouldBe true
          takes.contains("second") shouldBe true
          relinquishes.get() shouldBe true
        }
      }
    }
  }
}
