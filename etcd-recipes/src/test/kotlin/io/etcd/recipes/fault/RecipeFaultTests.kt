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

import com.pambrose.common.concurrent.BooleanMonitor
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.queue.DistributedQueue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Drives whole recipes through an etcd restart on a private Testcontainers node.
 * These are end-to-end checks that recipe state converges and blocked operations
 * complete once the server returns.
 */
class RecipeFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "PathChildrenCache converges with etcd contents across a restart" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        val cachePath = "$path/cache"
        PathChildrenCache(client, cachePath).start(true).use { cache ->
          client.putValue("$cachePath/a", "1")
          pollUntil(10.seconds) { cache.getCurrentData("a")?.asString == "1" } shouldBe true

          EtcdTestContainer.restart()

          client.putValue("$cachePath/b", "2")
          pollUntil(60.seconds) {
            cache.currentDataAsMap.mapValues { it.value.asString } == mapOf("a" to "1", "b" to "2")
          } shouldBe true
        }
      }
    }

    "ServiceCache reflects registrations made after a restart" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        val namesPath = "$path/discovery"
        val serviceName = "svc"
        val before = ServiceInstance(serviceName, "before")
        val after = ServiceInstance(serviceName, "after")

        ServiceCache(client, namesPath, serviceName).use { cache ->
          cache.start()
          client.putValue("$namesPath/$serviceName/${before.id}", before.toJson())
          pollUntil(10.seconds) { cache.instances.size == 1 } shouldBe true

          EtcdTestContainer.restart()

          client.putValue("$namesPath/$serviceName/${after.id}", after.toJson())
          pollUntil(60.seconds) {
            cache.instances.map { it.jsonPayload }.sorted() == listOf("after", "before")
          } shouldBe true
        }
      }
    }

    "candidate is elected after the leader relinquishes post-restart" {
      assumeFaultInjection()
      val electionPath = "$path/election"
      val releaseLeader = BooleanMonitor(false)
      val aLeaderships = AtomicInteger(0)
      val bLeaderships = AtomicInteger(0)

      connectToEtcd(urls) { clientA ->
        connectToEtcd(urls) { clientB ->
          val selectorA =
            LeaderSelector(
              clientA,
              electionPath,
              takeLeadershipBlock = { _ ->
                aLeaderships.incrementAndGet()
                releaseLeader.waitUntilTrue()
              },
            )
          val selectorB =
            LeaderSelector(
              clientB,
              electionPath,
              takeLeadershipBlock = { _ -> bLeaderships.incrementAndGet() },
            )

          selectorA.use { a ->
            selectorB.use { b ->
              a.start()
              pollUntil(15.seconds) { aLeaderships.get() == 1 } shouldBe true
              b.start()

              // The candidate's leader-key watch must survive the restart to ever
              // see the DELETE that follows the leader stepping down.
              EtcdTestContainer.restart()

              releaseLeader.set(true)
              pollUntil(60.seconds) { bLeaderships.get() == 1 } shouldBe true
              a.waitOnLeadershipComplete(30.seconds) shouldBe true
              b.waitOnLeadershipComplete(30.seconds) shouldBe true
            }
          }
        }
      }
    }

    "parked dequeue completes for an item enqueued after a restart" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        val queuePath = "$path/queue"
        DistributedQueue(client, queuePath).use { queue ->
          val result = AtomicReference<String?>()
          val error = AtomicReference<Throwable?>()
          val consumer =
            thread(name = "fault-dequeue") {
              try {
                result.set(queue.dequeue().asString)
              } catch (e: Throwable) {
                error.set(e)
              }
            }

          Thread.sleep(1_000) // let the dequeue park on its watch

          EtcdTestContainer.restart()

          connectToEtcd(urls) { producer -> DistributedQueue(producer, queuePath).use { it.enqueue("recovered") } }

          pollUntil(60.seconds) { result.get() != null || error.get() != null } shouldBe true
          error.get() shouldBe null
          result.get() shouldBe "recovered"
          consumer.join(5_000)
        }
      }
    }
  }
}
