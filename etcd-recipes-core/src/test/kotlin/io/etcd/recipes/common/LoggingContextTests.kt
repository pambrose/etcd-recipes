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

package io.etcd.recipes.common

import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.serviceInstance
import io.etcd.recipes.queue.DistributedWorkQueue
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.slf4j.MDC
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Background work carries a logging context: watch callbacks and lease heals run with the
 * MDC of the code that created them, a recipe's callbacks name the recipe under
 * [EtcdConnector.RECIPE_MDC_KEY], and the work queue's sweeper thread names its queue.
 * Otherwise their logs, on threads every recipe shares, can't be attributed.
 */
class LoggingContextTests : StringSpec() {
  private val base = "/common/${javaClass.simpleName}"

  private fun <T> withMdc(
    key: String,
    value: String,
    block: () -> T,
  ): T {
    MDC.put(key, value)
    return try {
      block()
    } finally {
      MDC.remove(key)
    }
  }

  init {
    "a watcher's callbacks run with the MDC of the code that created it" {
      connectToEtcd(urls) { client ->
        val key = "$base/watched"
        val seen = AtomicReference<String?>(null)
        withMdc("tenant", "acme") {
          client.watcher(key) { seen.store(MDC.get("tenant") ?: "<none>") }
        }.use {
          client.putValue(key, "v")
          pollUntil(10.seconds) { seen.load() != null } shouldBe true
          withClue("the watch callback lost its creator's MDC") { seen.load() shouldBe "acme" }
        }
        client.deleteKey(key)
      }
    }

    "a lease heal runs with the MDC of the code that created the healer" {
      connectToEtcd(urls) { client ->
        val key = "$base/healed"
        val establishedWith = CopyOnWriteArrayList<String>()
        val healer =
          withMdc("tenant", "acme") {
            client.selfHealingKeepAlive(2.seconds, LeaseResilience.DEFAULT) { lease ->
              establishedWith += MDC.get("tenant") ?: "<none>"
              client.transaction {
                Then(key.setTo("v", putOption { withLeaseId(lease.id) }))
              }.isSucceeded
            }
          }
        healer.use {
          client.leaseClient.revoke(client.getResponse(key).kvs.first().lease).get() // forces a heal
          pollUntil(20.seconds) { establishedWith.size >= 2 } shouldBe true
          withClue("the heal lost its creator's MDC") { establishedWith shouldBe ["acme", "acme"] }
        }
        client.deleteKey(key)
      }
    }

    "a PathChildrenCache's listeners run with the cache's identity" {
      connectToEtcd(urls) { client ->
        val path = "$base/cache"
        client.deleteChildren(path)
        val seen = AtomicReference<String?>(null)
        PathChildrenCache(client, path).use { cache ->
          cache.addListener { seen.store(MDC.get(EtcdConnector.RECIPE_MDC_KEY) ?: "<none>") }
          cache.start(PathChildrenCache.StartMode.NORMAL)
          client.putValue("$path/child", "v")
          pollUntil(10.seconds) { seen.load() != null } shouldBe true
          seen.load() shouldBe "PathChildrenCache[$path]"
        }
        client.deleteChildren(path)
      }
    }

    "a ServiceCache's listeners run with the cache's identity" {
      connectToEtcd(urls) { client ->
        val path = "$base/discovery"
        client.deleteChildren(path)
        val seen = AtomicReference<String?>(null)
        ServiceDiscovery(client, path).use { sd ->
          sd.serviceCache("svc").use { cache ->
            cache.addListenerForChanges { _, _, _, _ -> seen.store(MDC.get(EtcdConnector.RECIPE_MDC_KEY) ?: "<none>") }
            cache.start()
            sd.registerService(serviceInstance("svc", "payload"))
            pollUntil(10.seconds) { seen.load() != null } shouldBe true
            seen.load() shouldBe "ServiceCache[svc]"
          }
        }
        client.deleteChildren(path)
      }
    }

    "the work queue's sweeper thread names its queue" {
      connectToEtcd(urls) { client ->
        val path = "$base/work"
        client.deleteChildren(path)
        DistributedWorkQueue(client, path).use { queue ->
          queue.enqueue("job")
          queue.receive(10.seconds)?.ack() // a claim: its consumer lease starts the sweeper
          withClue("no sweeper thread names $path") {
            Thread.getAllStackTraces().keys.any { it.name == "workqueue-sweeper[$path]" } shouldBe true
          }
        }
        client.deleteChildren(path)
      }
    }
  }
}
