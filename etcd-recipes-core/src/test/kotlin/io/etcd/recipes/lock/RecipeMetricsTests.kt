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

import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.EtcdMetrics
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.queue.DistributedQueue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end proof that the recipes actually drive the [EtcdMetrics] seams: a mutex,
 * a semaphore, and a leader selector are each run with a recording metrics installed via
 * [ResilienceConfig.withMetrics], and the recorded events are asserted.
 */
class RecipeMetricsTests : StringSpec() {
  private val base = "/metrics/${javaClass.simpleName}"

  private class RecordingMetrics : EtcdMetrics {
    val lockWaits = CopyOnWriteArrayList<Boolean>()
    val lockHolds = AtomicInt(0)
    val leadership = CopyOnWriteArrayList<Boolean>()
    val queueOps = CopyOnWriteArrayList<String>()
    val cacheSyncs = CopyOnWriteArrayList<Int>()

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
      lockHolds.incrementAndFetch()
    }

    override fun incrementLeadershipTransition(
      path: String,
      becameLeader: Boolean,
    ) {
      leadership += becameLeader
    }

    override fun recordQueue(
      op: String,
      path: String,
      duration: Duration,
    ) {
      queueOps += op
    }

    override fun recordCacheSync(
      path: String,
      duration: Duration,
      size: Int,
    ) {
      cacheSyncs += size
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
        metrics.lockHolds.load() shouldBe 1
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
        metrics.lockHolds.load() shouldBe 1
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

    "a queue dequeue records a dequeue op" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val metrics = RecordingMetrics()
        DistributedQueue(client, "$base/queue", resilience = ResilienceConfig.DEFAULT.withMetrics(metrics))
          .use { queue ->
            queue.enqueue("hello")
            queue.dequeue()
          }
        metrics.queueOps shouldBe listOf("dequeue")
      }
    }

    "a path-children cache records an initial sync with the entry count" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val cachePath = "$base/cache"
        client.putValue("$cachePath/a", "1")
        client.putValue("$cachePath/b", "2")
        val metrics = RecordingMetrics()
        PathChildrenCache(client, cachePath, resilience = ResilienceConfig.DEFAULT.withMetrics(metrics))
          .use { cache ->
            cache.start(buildInitial = true)
            pollUntil(10.seconds) { metrics.cacheSyncs.isNotEmpty() } shouldBe true
          }
        metrics.cacheSyncs.first() shouldBe 2
      }
    }
  }
}
