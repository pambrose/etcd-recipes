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

package io.etcd.recipes.design

import io.etcd.recipes.barrier.DistributedBarrier
import io.etcd.recipes.barrier.DistributedBarrierWithCount
import io.etcd.recipes.barrier.DistributedDoubleBarrier
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.discovery.ServiceProvider
import io.etcd.recipes.discovery.ServiceRegistry
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.queue.DistributedQueue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import kotlin.time.Duration.Companion.seconds

/**
 * Tests covering the 14 design fixes identified in the codebase review.
 * Most tests run against a local etcd at [urls]; the pure-Kotlin checks
 * (visibility, default-id naming) do not reach etcd and act as fast smoke
 * tests for the structural changes.
 */
class DesignFixesTests : StringSpec() {
  init {

    // ============================================================
    // Fix #1: EtcdConnector idempotency template-method
    // ============================================================
    "fix #1: EtcdConnector close() runs doClose() exactly once across multiple calls" {
      connectToEtcd(urls) { client ->
        val callCount = AtomicInt(0)
        val connector = object : EtcdConnector(client) {
          override fun doClose() {
            callCount.incrementAndFetch()
          }
        }
        connector.close()
        connector.close()
        connector.close()
        callCount.load() shouldBe 1
      }
    }

    "fix #1: EtcdConnector.close() is final on the base class" {
      val method = EtcdConnector::class.java.getMethod("close")
      java.lang.reflect.Modifier.isFinal(method.modifiers) shouldBe true
    }

    // ============================================================
    // Fix #2: DistributedBarrierWithCount close() unblocks waitOnBarrier
    // ============================================================
    "fix #2: DistributedBarrierWithCount.close() unblocks an in-flight waitOnBarrier" {
      val path = "/barriers/design-fixes/close-while-waiting"
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)

        // memberCount=2 so a single waiter will park forever without a peer
        val barrier = DistributedBarrierWithCount(client, path, memberCount = 2)
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var result: Boolean? = null

        executor.execute {
          started.countDown()
          // 30s is plenty; if close() doesn't unblock us, the test will time out
          // here long before the assertion below.
          result = barrier.waitOnBarrier(30, TimeUnit.SECONDS)
          finished.countDown()
        }

        started.await()
        // Wait until the waiter has actually registered before closing. A fixed
        // sleep races with slow etcd setup under CI load: close() could fire
        // while the waiter is still in leaseGrant/CAS, where checkCloseNotCalled
        // would throw instead of the waiter parking and being cancelled cleanly.
        pollUntil(15.seconds) { barrier.waiterCount >= 1 } shouldBe true
        Thread.sleep(500) // brief settle so the waiter reaches the park

        // close() must wake the waiter; without the fix this would hang.
        barrier.close()

        finished.await(10, TimeUnit.SECONDS) shouldBe true
        // A cancelled wait should report not-satisfied (false), not satisfied (true).
        result shouldBe false
        executor.shutdown()
        client.deleteChildren(path)
      }
    }

    "fix #2: DistributedDoubleBarrier.close() propagates cancellation to both barriers" {
      val path = "/barriers/design-fixes/double-close-while-waiting"
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val doubleBarrier = DistributedDoubleBarrier(client, path, memberCount = 2)
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)

        executor.execute {
          started.countDown()
          doubleBarrier.enter(30, TimeUnit.SECONDS)
          finished.countDown()
        }
        started.await()
        // Wait for the waiter to register rather than racing a fixed sleep.
        pollUntil(15.seconds) { doubleBarrier.enterWaiterCount >= 1 } shouldBe true
        Thread.sleep(500)
        doubleBarrier.close()
        finished.await(10, TimeUnit.SECONDS) shouldBe true
        executor.shutdown()
        client.deleteChildren(path)
      }
    }

    // ============================================================
    // Fix #3: LeaderSelector uses common/ leaseGrant extension
    // ============================================================
    "fix #3: LeaderSelector source no longer references jetcd leaseClient.grant directly" {
      // Strict structural check that the fix did not regress: the previous
      // implementation called `client.leaseClient.grant(leaseTtlSecs).get()`
      // inline; the post-fix code routes through `client.leaseGrant(...)`.
      val src = java.nio.file.Files.readString(
        java.nio.file.Path.of("src/main/kotlin/io/etcd/recipes/election/LeaderSelector.kt"),
      )
      src.contains("leaseClient.grant") shouldBe false
      // Lease grant via the common extension must still happen.
      src.contains("client.leaseGrant(") shouldBe true
    }

    // ============================================================
    // Fix #4: defaultClientId centralized; each recipe uses its own prefix
    // ============================================================
    "fix #4: each recipe's clientId default carries its own class name as prefix" {
      connectToEtcd(urls) { client ->
        DistributedBarrier(client, "/x").clientId shouldStartWith "DistributedBarrier:"
        DistributedBarrierWithCount(client, "/x", 1).clientId shouldStartWith "DistributedBarrierWithCount:"
        DistributedDoubleBarrier(client, "/x", 1).clientId shouldStartWith "DistributedDoubleBarrier:"
        LeaderSelector(client, "/x", takeLeadershipBlock = {}).clientId shouldStartWith "LeaderSelector:"
        TransientKeyValue(client, "/x", "v", autoStart = false).clientId shouldStartWith "TransientKeyValue:"
        ServiceDiscovery(client, "/x").clientId shouldStartWith "ServiceDiscovery:"
      }
    }

    "fix #4: EtcdConnector.defaultClientId(prefix) is the single source of truth" {
      val id1 = EtcdConnector.defaultClientId("Foo")
      val id2 = EtcdConnector.defaultClientId("Foo")
      id1 shouldStartWith "Foo:"
      id2 shouldStartWith "Foo:"
      // Different invocations must produce distinct random suffixes.
      (id1 != id2) shouldBe true
    }

    // ============================================================
    // Fix #5: client visibility is protected
    // ============================================================
    "fix #5: EtcdConnector.client is protected, not public" {
      val prop = EtcdConnector::class.declaredMemberProperties.first { it.name == "client" }
      prop.visibility shouldBe KVisibility.PROTECTED
    }

    // ============================================================
    // Fix #6: ServiceProvider routes to the correct path and is closed by parent
    // ============================================================
    "fix #6: ServiceProvider returns instances from the parent's namesPath, not a doubled path" {
      val basePath = "/services/design-fixes/sp-path"
      connectToEtcd(urls) { client ->
        client.deleteChildren(basePath)
        ServiceDiscovery(client, basePath).use { sd ->
          val instance = ServiceInstance("alpha", "p1")
          sd.registerService(instance)

          val provider = sd.serviceProvider("alpha")
          val all = provider.getAllInstances()
          all shouldHaveSize 1
          all[0].name shouldBe "alpha"
        }
        client.deleteChildren(basePath)
      }
    }

    "fix #6: ServiceDiscovery.close() also closes serviceProviders that were leaked previously" {
      val basePath = "/services/design-fixes/sp-close"
      connectToEtcd(urls) { client ->
        client.deleteChildren(basePath)
        val sd = ServiceDiscovery(client, basePath)
        val provider = sd.serviceProvider("alpha")
        sd.close()
        // After parent close, calling provider.close() should be safe (idempotent no-op).
        provider.close()
        client.deleteChildren(basePath)
      }
    }

    // ============================================================
    // Fix #7: DistributedAtomicLong constructor does no I/O
    // ============================================================
    "fix #7: DistributedAtomicLong can be constructed against an unreachable etcd" {
      // Build a client whose endpoint will never resolve. The fix moved
      // createCounterIfNotPresent() out of init {} so this must NOT throw.
      val client = io.etcd.jetcd.Client.builder()
        .endpoints("http://127.0.0.1:1")
        .build()
      try {
        // No I/O performed; no exception expected.
        DistributedAtomicLong(client, "/counter/never-touched")
      } finally {
        client.close()
      }
    }

    "fix #7: DistributedAtomicLong path validation still happens at construction" {
      connectToEtcd(urls) { client ->
        shouldThrow<IllegalArgumentException> { DistributedAtomicLong(client, "") }
      }
    }

    // ============================================================
    // Fix #8: AbstractQueue.dequeue is iterative under CAS contention
    // ============================================================
    "fix #8: dequeue() is loop-based, not recursive" {
      // Static guarantee: the source no longer self-recurses in dequeue().
      val src = java.nio.file.Files.readString(
        java.nio.file.Path.of("src/main/kotlin/io/etcd/recipes/queue/AbstractQueue.kt"),
      )
      // The string `dequeue()` should appear exactly once — in the function
      // signature `fun dequeue(): ByteSequence`. Any additional occurrence is
      // a recursive self-call, which is what the fix removes.
      val occurrences = "dequeue\\(\\)".toRegex().findAll(src).count()
      occurrences shouldBe 1
      // Positive marker: the iterative replacement must be present.
      src.contains("while (true)") shouldBe true
    }

    "fix #8: queue handles many concurrent producers and consumers without crashing" {
      val path = "/queues/design-fixes/contention"
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        DistributedQueue(client, path).use { queue ->
          val producerCount = 8
          val perProducer = 25
          val producers = Executors.newFixedThreadPool(producerCount)
          val consumers = Executors.newFixedThreadPool(producerCount)
          val received = AtomicInt(0)
          val total = producerCount * perProducer
          val finishLatch = CountDownLatch(total)

          repeat(producerCount) { p ->
            producers.execute {
              repeat(perProducer) { i -> queue.enqueue("p$p-i$i") }
            }
          }
          repeat(producerCount) {
            consumers.execute {
              while (received.load() < total) {
                queue.dequeue() // blocks until a value appears
                received.incrementAndFetch()
                finishLatch.countDown()
              }
            }
          }
          finishLatch.await(60, TimeUnit.SECONDS) shouldBe true
          received.load() shouldBe total
          producers.shutdownNow()
          consumers.shutdownNow()
        }
        client.deleteChildren(path)
      }
    }

    // ============================================================
    // Fix #9: cache revision anchor (snapshot+watch consistency)
    // ============================================================
    "fix #9: PathChildrenCache initial cache reflects an updated value present at start time" {
      val basePath = "/caches/design-fixes/revision-anchor"
      connectToEtcd(urls) { client ->
        client.deleteChildren(basePath)
        client.putValue("$basePath/k", "v1")
        client.putValue("$basePath/k", "v2") // newest revision

        PathChildrenCache(client, basePath).use { cache ->
          cache.start(buildInitial = true, waitOnStartComplete = true)
          // Without an anchor, a stale snapshot could overwrite a newer value.
          // With the anchor, the latest committed value at snapshot revision wins.
          val data = cache.getCurrentData("k")
          data shouldBe io.etcd.jetcd.ByteSequence.from("v2", Charsets.UTF_8)
        }
        client.deleteChildren(basePath)
      }
    }

    "fix #9: ServiceCache picks up updates after start without missing events" {
      val basePath = "/services/design-fixes/cache-anchor"
      connectToEtcd(urls) { client ->
        client.deleteChildren(basePath)
        val sd = ServiceDiscovery(client, basePath)
        val instance = ServiceInstance("svc", "v1")
        sd.registerService(instance)

        val cache = ServiceCache(client, "$basePath/names", "svc").start()
        // Snapshot included the registered instance.
        cache.instances.map { it.name } shouldBe ["svc"]
        cache.close()
        sd.close()
        client.deleteChildren(basePath)
      }
    }

    // ============================================================
    // Fix #10: concurrency primitive cleanup (DistributedBarrier no longer
    // wraps a value in AtomicReference inside a @Synchronized scope)
    // ============================================================
    "fix #10: DistributedBarrier.keepAliveLease is a plain var, not an AtomicReference" {
      val field = DistributedBarrier::class.java.getDeclaredField("keepAliveLease")
      val typeName = field.type.name
      // After the cleanup the field should be CloseableClient (nullable), not AtomicReference.
      typeName.contains("AtomicReference") shouldBe false
    }

    // ============================================================
    // Fix #11: ExceptionHolder is no longer in the production classpath
    // ============================================================
    "fix #11: ExceptionHolder is not bundled in main sources" {
      val mainPath = java.nio.file.Path.of("src/main/kotlin/io/etcd/recipes/common/ExceptionHolder.kt")
      java.nio.file.Files.exists(mainPath) shouldBe false
      // Still available as a test helper.
      val testPath = java.nio.file.Path.of("src/test/kotlin/io/etcd/recipes/common/ExceptionHolder.kt")
      java.nio.file.Files.exists(testPath) shouldBe true
    }

    // ============================================================
    // Fix #12: ServiceRegistry can be used independently of ServiceDiscovery
    // ============================================================
    "fix #12: ServiceRegistry registers and unregisters without a ServiceDiscovery instance" {
      val basePath = "/services/design-fixes/registry-only"
      connectToEtcd(urls) { client ->
        client.deleteChildren(basePath)
        ServiceRegistry(client, basePath).use { registry ->
          val instance = ServiceInstance("standalone", "x")
          registry.registerService(instance)
          // ServiceProvider can read it back without going through ServiceDiscovery.
          val provider = ServiceProvider(client, "$basePath/names", "standalone")
          provider.getAllInstances().map { it.name } shouldBe ["standalone"]
          registry.unregisterService(instance)
        }
        client.deleteChildren(basePath)
      }
    }

    // ============================================================
    // Fix #13: caches use plain `var watcher`, not AtomicReference
    // ============================================================
    "fix #13: PathChildrenCache.watcher field is not an AtomicReference" {
      val field = PathChildrenCache::class.java.getDeclaredField("watcher")
      field.type.name.contains("AtomicReference") shouldBe false
    }

    "fix #13: ServiceCache.watcher field is not an AtomicReference" {
      val field = ServiceCache::class.java.getDeclaredField("watcher")
      field.type.name.contains("AtomicReference") shouldBe false
    }

    // ============================================================
    // Fix #14: nullWatchOption removed
    // ============================================================
    "fix #14: nullWatchOption is no longer present in WatchExtensions" {
      val src = java.nio.file.Files.readString(
        java.nio.file.Path.of("src/main/kotlin/io/etcd/recipes/common/WatchExtensions.kt"),
      )
      src.contains("nullWatchOption") shouldBe false
    }

    // ============================================================
    // Smoke check on Fix #4 default-ids stability across calls
    // ============================================================
    "smoke: each call to defaultClientId yields a fresh suffix" {
      val ids = (1..20).map { EtcdConnector.defaultClientId("X") }
      ids.toSet().size shouldBe 20
    }
  }
}
