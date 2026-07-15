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

package io.etcd.recipes.lock

import io.etcd.recipes.common.EtcdMetrics
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.urls
import io.etcd.recipes.election.LeaderSelector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * End-to-end proof that the recipes actually drive the [EtcdMetrics] seams: a mutex,
 * a semaphore, and a leader selector are each run with a recording metrics installed via
 * [ResilienceConfig.withMetrics], and the recorded events are asserted.
 */
class RecipeMetricsTests : StringSpec() {
  private val base = "/metrics/${javaClass.simpleName}"

  private class RecordingMetrics : EtcdMetrics {
    val lockWaits = CopyOnWriteArrayList<Boolean>()
    val lockHolds = AtomicInteger(0)
    val leadership = CopyOnWriteArrayList<Boolean>()

    override fun recordLockWait(
      path: String,
      duration: Duration,
      acquired: Boolean,
    ) {
      lockWaits += acquired
    }

    override fun recordLockHold(
      path: String,
      duration: Duration,
    ) {
      lockHolds.incrementAndGet()
    }

    override fun incrementLeadershipTransition(
      path: String,
      becameLeader: Boolean,
    ) {
      leadership += becameLeader
    }
  }

  init {
    "mutex lock then unlock records one acquired wait and one hold" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val metrics = RecordingMetrics()
        DistributedMutex(client, "$base/mutex", resilience = ResilienceConfig.DEFAULT.withMetrics(metrics))
          .use { mutex ->
            mutex.lock()
            mutex.unlock()
          }
        metrics.lockWaits shouldBe listOf(true)
        metrics.lockHolds.get() shouldBe 1
      }
    }

    "semaphore acquire then release records one acquired wait and one hold" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val metrics = RecordingMetrics()
        DistributedSemaphore(client, "$base/sem", 1, resilience = ResilienceConfig.DEFAULT.withMetrics(metrics))
          .use { semaphore ->
            semaphore.acquire()
            semaphore.release()
          }
        metrics.lockWaits shouldBe listOf(true)
        metrics.lockHolds.get() shouldBe 1
      }
    }

    "a leadership term records a take then a relinquish transition" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val metrics = RecordingMetrics()
        LeaderSelector(
          client,
          "$base/election",
          takeLeadershipBlock = { }, // return immediately → relinquish
          resilience = ResilienceConfig.DEFAULT.withMetrics(metrics),
        ).use { selector ->
          selector.start()
          selector.waitOnLeadershipComplete()
        }
        metrics.leadership shouldBe listOf(true, false)
      }
    }
  }
}
