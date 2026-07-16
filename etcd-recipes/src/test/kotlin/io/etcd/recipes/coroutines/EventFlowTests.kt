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

package io.etcd.recipes.coroutines

import com.pambrose.common.concurrent.BooleanMonitor
import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.cache.NodeCacheEvent
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.cache.PathChildrenCacheEvent
import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.election.LeaderSelector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Flow event surfaces: cache child events, leadership hand-off, service-cache changes,
 * and connection state, with the shared lifecycle contract that collection never
 * starts or closes the recipe and cancellation unregisters the listener.
 */
class EventFlowTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  private suspend fun <T> withScope(body: suspend (CoroutineScope) -> T): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      return body(scope)
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }

  init {
    "cache eventsAsFlow delivers child add, update, and remove in order" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/cache"
        val seen = CopyOnWriteArrayList<Pair<PathChildrenCacheEvent.Type, String>>()

        PathChildrenCache(client, path).use { cache ->
          cache.start(buildInitial = true)
          cache.waitOnStartComplete(10.seconds) shouldBe true

          withScope { scope ->
            scope.launch {
              cache.eventsAsFlow().collect { e -> seen += e.type to e.childName }
            }
            delay(1_000) // let the collector subscribe

            client.putValue("$path/k", "v1")
            client.putValue("$path/k", "v2")
            client.deleteChildren("$path")

            untilTrue(15.seconds) { seen.size == 3 } shouldBe true
            seen.map { it.first } shouldContainExactly
              listOf(
                PathChildrenCacheEvent.Type.CHILD_ADDED,
                PathChildrenCacheEvent.Type.CHILD_UPDATED,
                PathChildrenCacheEvent.Type.CHILD_REMOVED,
              )
          }
        }
      }
    }

    "nodeCache eventsAsFlow delivers CREATED, UPDATED, and DELETED in order" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val key = "$base/node"
        val seen = CopyOnWriteArrayList<NodeCacheEvent.Type>()

        NodeCache(client, key, StringCodec).use { cache ->
          cache.start()

          withScope { scope ->
            scope.launch { cache.eventsAsFlow().collect { e -> seen += e.type } }
            delay(1_000) // let the collector subscribe

            client.putValue(key, "v1")
            client.putValue(key, "v2")
            client.deleteKey(key)

            untilTrue(15.seconds) { seen.size == 3 } shouldBe true
            seen shouldContainExactly
              listOf(
                NodeCacheEvent.Type.CREATED,
                NodeCacheEvent.Type.UPDATED,
                NodeCacheEvent.Type.DELETED,
              )
          }
        }
      }
    }

    "cache flow stops delivering after the collector is cancelled and the cache keeps running" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/cache-cancel"
        val seen = CopyOnWriteArrayList<String>()

        PathChildrenCache(client, path).use { cache ->
          cache.start(buildInitial = true)
          cache.waitOnStartComplete(10.seconds) shouldBe true

          withScope { scope ->
            val job = scope.launch { cache.eventsAsFlow().collect { seen += it.childName } }
            delay(1_000)
            client.putValue("$path/a", "1")
            untilTrue(15.seconds) { seen.size == 1 } shouldBe true

            job.cancelAndJoin()
            client.putValue("$path/b", "2")
            // The cache's own watch is still live, so it reconciles "b" — proving the
            // recipe keeps running — while the cancelled flow delivers nothing more.
            untilTrue(15.seconds) { cache.getCurrentData("b") != null } shouldBe true
            seen.size shouldBe 1
          }
        }
      }
    }

    "leadershipAsFlow reports the elected leader and the current leader for a late collector" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/election"
        val events = CopyOnWriteArrayList<LeadershipEvent>()
        val released = BooleanMonitor(false)

        LeaderSelector(
          client,
          path,
          takeLeadershipBlock = { released.waitUntilTrue() },
          clientId = "node-alpha",
        ).use { selector ->
          selector.start()
          untilTrue(20.seconds) {
            client.getResponse("$path/LEADER").kvs.isNotEmpty()
          } shouldBe true

          withScope { scope ->
            scope.launch { client.leadershipAsFlow(path).collect { events += it } }
            // Late collector still sees the sitting leader first
            untilTrue(15.seconds) {
              events.any { it is LeadershipEvent.Elected && it.leaderName == "node-alpha" }
            } shouldBe true
          }
          released.set(true)
          selector.waitOnLeadershipComplete(20.seconds) shouldBe true
        }
      }
    }

    "serviceCache eventsAsFlow reports registrations" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/discovery"
        val events = CopyOnWriteArrayList<ServiceCacheEvent>()

        ServiceDiscovery(client, path).use { sd ->
          val cache: ServiceCache = sd.serviceCache("TestService")
          cache.use {
            cache.start()
            withScope { scope ->
              scope.launch { cache.eventsAsFlow().collect { events += it } }
              delay(1_000)

              val instance = ServiceInstance("TestService", "payload")
              sd.registerService(instance)

              untilTrue(15.seconds) {
                events.any { it.isAdd && it.serviceInstance?.name == "TestService" }
              } shouldBe true
              sd.unregisterService(instance)
            }
          }
        }
      }
    }

    "connectionStateAsFlow emits the current state on collection" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/conn"
        val states = CopyOnWriteArrayList<io.etcd.recipes.common.ConnectionState>()

        PathChildrenCache(client, path).use { cache ->
          cache.start(buildInitial = true)
          cache.waitOnStartComplete(10.seconds) shouldBe true
          withScope { scope ->
            scope.launch { cache.connectionStateAsFlow().collect { states += it } }
            untilTrue(10.seconds) {
              states.contains(io.etcd.recipes.common.ConnectionState.CONNECTED)
            } shouldBe true
          }
        }
      }
    }
  }
}
