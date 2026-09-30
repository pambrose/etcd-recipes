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

package io.etcd.recipes.discovery

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.cache.PathChildrenCacheEvent
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Discovery must survive what other writers leave in etcd, and must not accumulate state:
 *
 * - a malformed or newer-schema instance entry is skipped (and reported), never an outage
 *   for the whole service — in the cache, the one-shot queries, and a provider's direct reads;
 * - `ServiceProvider` counts errors within a `downPeriod` window and forgets instances that
 *   are gone or whose window has lapsed;
 * - the façade doesn't keep closed caches and providers, `queryForNames()` returns service
 *   names, an unstarted cache closes quietly, and a cache's own start worker doesn't keep
 *   the JVM alive.
 */
class DiscoveryRobustnessTests : StringSpec() {
  private val base = "/discovery/${javaClass.simpleName}"

  // A newer library version's instance: the same JSON plus a field this version doesn't know.
  private fun newerSchemaJson(instance: ServiceInstance) = instance.toJson().replaceFirst("{", "{\"weight\":5,")

  // A mocked client whose ranged GET serves whatever [current] returns at call time.
  private fun clientServing(
    all: List<Pair<String, String>>,
    current: () -> List<Pair<String, String>>,
  ): Client {
    val kvs =
      all.associateWith { (id, json) ->
        mockk<KeyValue> {
          every { key } returns "/services/names/svc/$id".asByteSequence
          every { value } returns json.asByteSequence
        }
      }
    val resp =
      mockk<GetResponse> {
        every { this@mockk.kvs } answers { current().map { kvs.getValue(it) } }
        every { isMore } returns false
      }
    val kv = mockk<KV> { every { get(any(), any()) } returns CompletableFuture.completedFuture(resp) }
    return mockk { every { kvClient } returns kv }
  }

  private fun clientServing(entries: List<Pair<String, String>>) = clientServing(entries) { entries }

  init {
    "a malformed or newer-schema instance doesn't break the cache's reads" {
      connectToEtcd(urls) { client ->
        val path = "$base/cache-priming"
        client.deleteChildren(path)
        val namesPath = path.appendToPath("/names")
        val good = serviceInstance("svc", "good")
        val newer = serviceInstance("svc", "newer")
        client.putValue(namesPath.appendToPath("svc/${good.id}"), good.toJson())
        client.putValue(namesPath.appendToPath("svc/${newer.id}"), newerSchemaJson(newer))
        client.putValue(namesPath.appendToPath("svc/bad"), "not json")

        ServiceCache(client, namesPath, "svc").start().use { cache ->
          withClue("one bad entry broke reads of every instance") {
            cache.instances.map { it.jsonPayload } shouldContainExactlyInAnyOrder ["good", "newer"]
          }
          withClue("the skipped entry wasn't reported") {
            pollUntil(5.seconds) { cache.exceptions.isNotEmpty() } shouldBe true
            cache.exceptions.single().message shouldContain "svc/bad"
          }
        }
        client.deleteChildren(path)
      }
    }

    "a malformed entry arriving through the watch is skipped, and replaces a stale instance" {
      connectToEtcd(urls) { client ->
        val path = "$base/cache-watch"
        client.deleteChildren(path)
        val namesPath = path.appendToPath("/names")
        val good = serviceInstance("svc", "good")
        val spoiled = serviceInstance("svc", "spoiled")
        client.putValue(namesPath.appendToPath("svc/${good.id}"), good.toJson())
        client.putValue(namesPath.appendToPath("svc/${spoiled.id}"), spoiled.toJson())

        ServiceCache(client, namesPath, "svc").start().use { cache ->
          val events = CopyOnWriteArrayList<Pair<EventType, String>>()
          cache.addListenerForChanges { type, _, key, _ -> events += type to key }
          client.putValue(namesPath.appendToPath("svc/bad"), "not json")
          client.putValue(namesPath.appendToPath("svc/${spoiled.id}"), "{\"truncated\":")
          pollUntil(10.seconds) { cache.exceptions.size >= 2 } shouldBe true
          withClue("a bad entry from the watch broke reads of every instance") {
            cache.instances.map { it.jsonPayload } shouldBe ["good"]
          }
          withClue("listeners weren't told the overwritten instance is gone") {
            events shouldBe [EventType.DELETE to "svc/${spoiled.id}"]
          }
        }
        client.deleteChildren(path)
      }
    }

    "queryForInstances skips a malformed entry" {
      connectToEtcd(urls) { client ->
        val path = "$base/query"
        client.deleteChildren(path)
        ServiceDiscovery(client, path).use { sd ->
          val good = serviceInstance("svc", "good")
          sd.registerService(good)
          client.putValue(path.appendToPath("/names/svc/bad"), "not json")
          withClue("one bad entry broke queryForInstances") { sd.queryForInstances("svc") shouldBe [good] }
          pollUntil(5.seconds) { sd.exceptions.isNotEmpty() } shouldBe true
        }
        client.deleteChildren(path)
      }
    }

    "a provider's direct reads skip a malformed entry" {
      val good = serviceInstance("svc", "good")
      val entries = ["good" to good.toJson(), "bad" to "not json"]
      val provider = ServiceProvider(clientServing(entries), "/services/names", "svc")
      withClue("one bad entry broke getInstance") { provider.getInstance() shouldBe good }
      pollUntil(5.seconds) { provider.exceptions.isNotEmpty() } shouldBe true
    }

    "an instance JSON with fields this version doesn't know still decodes" {
      val instance = serviceInstance("svc", "payload")
      ServiceInstance.toObject(newerSchemaJson(instance)) shouldBe instance
    }

    "errors further apart than the down period don't eject an instance" {
      val alive = serviceInstance("svc", "alive")
      val flaky = serviceInstance("svc", "flaky")
      val provider =
        ServiceProvider(
          clientServing(["alive" to alive.toJson(), "flaky" to flaky.toJson()]),
          "/services/names",
          "svc",
          strategy = RoundRobinStrategy(),
          errorThreshold = 2,
          downPeriod = 300.milliseconds,
        )
      provider.noteError(flaky)
      Thread.sleep(500)
      provider.noteError(flaky) // the first error's window has lapsed: this is the first again
      withClue("two errors 500 ms apart ejected the instance for a 300 ms window") {
        (1..4).map { provider.getInstance() } shouldBe [alive, flaky, alive, flaky]
      }
    }

    "down entries for vanished instances and lapsed windows are pruned" {
      val alive = serviceInstance("svc", "alive")
      val flaky = serviceInstance("svc", "flaky")
      val gone = serviceInstance("svc", "gone")
      val provider =
        ServiceProvider(
          clientServing(["alive" to alive.toJson(), "flaky" to flaky.toJson()]),
          "/services/names",
          "svc",
          errorThreshold = 3,
          downPeriod = 300.milliseconds,
        )
      provider.noteError(gone)
      provider.getInstance()
      withClue("an instance that's no longer registered kept its down entry") { provider.downEntryCount shouldBe 0 }

      provider.noteError(flaky)
      provider.getInstance()
      withClue("an entry inside its window was dropped") { provider.downEntryCount shouldBe 1 }
      Thread.sleep(500)
      provider.getInstance()
      withClue("an entry whose window lapsed was kept") { provider.downEntryCount shouldBe 0 }
    }

    "queryForNames returns each service name once" {
      connectToEtcd(urls) { client ->
        val path = "$base/names"
        client.deleteChildren(path)
        ServiceDiscovery(client, path).use { sd ->
          sd.registerService(serviceInstance("alpha", "1"))
          sd.registerService(serviceInstance("alpha", "2"))
          sd.registerService(serviceInstance("beta", "3"))
          sd.queryForNames() shouldBe ["alpha", "beta"]
        }
        client.deleteChildren(path)
      }
    }

    "the façade doesn't keep caches and providers after they close" {
      connectToEtcd(urls) { client ->
        val path = "$base/tracked"
        client.deleteChildren(path)
        ServiceDiscovery(client, path).use { sd ->
          repeat(10) {
            sd.withServiceCache("svc") { start() }
            sd.withServiceProvider("svc") { start() }
          }
          withClue("closed caches and providers are still tracked") { sd.trackedCount shouldBeLessThanOrEqual 2 }
        }
        client.deleteChildren(path)
      }
    }

    "an unstarted ServiceCache closes quietly" {
      connectToEtcd(urls) { client ->
        ServiceCache(client, "$base/unstarted/names", "svc").close()
      }
    }

    "a PathChildrenCache's own start worker is a daemon thread" {
      connectToEtcd(urls) { client ->
        val path = "$base/daemon"
        client.deleteChildren(path)
        val daemon = AtomicReference<Boolean?>(null)
        PathChildrenCache(client, path).use { cache ->
          cache.addListener { event ->
            if (event.type == PathChildrenCacheEvent.Type.INITIALIZED) daemon.store(Thread.currentThread().isDaemon)
          }
          cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT)
          pollUntil(10.seconds) { daemon.load() != null } shouldBe true
          withClue("the start worker would keep the JVM alive") { daemon.load() shouldBe true }
        }
      }
    }
  }
}
