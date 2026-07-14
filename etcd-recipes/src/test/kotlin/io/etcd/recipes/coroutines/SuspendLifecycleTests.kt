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
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Suspend surface for counter, keyvalue, cache, election, and discovery: start
 * variants, waits, CAS counter arithmetic, and register/query round-trips.
 */
class SuspendLifecycleTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  init {
    "concurrent awaitAdd lands every increment" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        DistributedAtomicLong(client, "$base/counter").use { counter ->
          coroutineScope {
            repeat(4) {
              launch(Dispatchers.Default) {
                repeat(5) { counter.awaitAdd(1) }
              }
            }
          }
          counter.awaitGet() shouldBe 20L
        }
      }
    }

    "path children cache awaitStart builds the initial cache" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/cache"
        client.putValue("$path/a", "1")
        client.putValue("$path/b", "2")

        PathChildrenCache(client, path).use { cache ->
          cache.awaitStart(buildInitial = true)
          cache.awaitStartComplete(10.seconds) shouldBe true
          cache.currentData.size shouldBe 2
        }
      }
    }

    "leader selector awaitStart and awaitLeadershipComplete" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val elected = BooleanMonitor(false)

        LeaderSelector(
          client,
          "$base/election",
          takeLeadershipBlock = { elected.set(true) },
        ).use { selector ->
          selector.awaitStart()
          selector.awaitLeadershipComplete(30.seconds) shouldBe true
          elected.get() shouldBe true
        }
      }
    }

    "transient key value awaitStart publishes the key" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/tkv"

        TransientKeyValue(client, path, "alive", autoStart = false).use { tkv ->
          tkv.awaitStart()
          untilTrue(10.seconds) { client.getValue(path, "") == "alive" } shouldBe true
        }
      }
    }

    "service discovery suspend round-trip" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)

        ServiceDiscovery(client, "$base/discovery").use { sd ->
          val service = ServiceInstance("TestService", "payload")
          sd.awaitRegisterService(service)

          sd.awaitQueryForNames().size shouldBe 1
          sd.awaitQueryForInstances("TestService").size shouldBe 1
          sd.awaitQueryForInstance("TestService", service.id).name shouldBe "TestService"

          val cache = sd.serviceCache("TestService")
          cache.use {
            cache.awaitStart()
            untilTrue(10.seconds) { cache.instances.size == 1 } shouldBe true
          }

          sd.awaitUnregisterService(service)
          sd.awaitQueryForInstances("TestService").size shouldBe 0
        }
      }
    }
  }
}
