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

package io.etcd.recipes.cache

import io.etcd.jetcd.ByteSequence
import io.etcd.recipes.cache.PathChildrenCache.StartMode.POST_INITIALIZED_EVENT
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_REMOVED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_UPDATED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.INITIALIZED
import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * `PathChildrenCache`'s primed start and rebuild paths:
 *
 * - a primed start that can't load its snapshot fails (or reports LOST) instead of
 *   presenting a healthy, empty cache that will never update;
 * - INITIALIZED carries the snapshot and precedes every later event, with one snapshot
 *   shared by every listener;
 * - an INITIALIZED listener may call `rebuild()` or `close()` without deadlocking;
 * - `rebuild()` never resurrects a key deleted while its snapshot was in flight;
 * - a typed listener that throws, or a child that can't be decoded, doesn't keep an
 *   event from the other typed listeners.
 */
class PathChildrenCacheLifecycleTests : StringSpec() {
  private val base = "/cache/${javaClass.simpleName}"

  private fun denied() = StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("denied"))

  // Decodes a child's value as an Int, so a non-numeric payload is undecodable.
  private object IntCodec : EtcdCodec<Int> {
    override fun encode(value: Int): ByteSequence = value.toString().asByteSequence

    override fun decode(bytes: ByteSequence): Int = bytes.asString.toInt()
  }

  init {
    "a primed start that can't load its snapshot throws instead of reporting a healthy empty cache" {
      connectToEtcd(urls) { etcd ->
        val client = HookedClient(etcd)
        PathChildrenCache(client, "$base/load-fails").use { cache ->
          client.beforeGet.store { throw denied() }
          shouldThrow<EtcdRecipeRuntimeException> { cache.start(true) }
          cache.isHealthy() shouldBe false
          cache.connectionState shouldBe ConnectionState.LOST
        }
      }
    }

    "a primed start without waiting reports LOST and fires no INITIALIZED when the load fails" {
      connectToEtcd(urls) { etcd ->
        val client = HookedClient(etcd)
        PathChildrenCache(client, "$base/load-fails-no-wait").use { cache ->
          val events = CopyOnWriteArrayList<PathChildrenCacheEvent>()
          cache.addListener { events += it }
          client.beforeGet.store { throw denied() }
          cache.start(POST_INITIALIZED_EVENT, waitOnStartComplete = false)
          shouldThrow<EtcdRecipeRuntimeException> { cache.waitOnStartComplete(10.seconds) }
          cache.connectionState shouldBe ConnectionState.LOST
          withClue("INITIALIZED reported an empty prefix that was never loaded") { events.shouldBeEmpty() }
        }
      }
    }

    "INITIALIZED carries the snapshot and precedes every later event" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/initialized-order"
        etcd.deleteChildren(path)
        etcd.putValue("$path/x", "v1")
        val client = HookedClient(etcd)
        PathChildrenCache(client, path).use { cache ->
          val first = CopyOnWriteArrayList<PathChildrenCacheEvent>()
          val second = CopyOnWriteArrayList<PathChildrenCacheEvent>()
          cache.addListener { first += it }
          cache.addListener { second += it }
          // Once the watch is up, change x and wait for its event before start() goes on
          client.afterWatch.store {
            etcd.putValue("$path/x", "v2")
            pollUntil(10.seconds) { first.any { it.type == CHILD_UPDATED } }
          }
          cache.start(POST_INITIALIZED_EVENT)
          pollUntil(10.seconds) { first.size == 2 && second.size == 2 } shouldBe true

          first.map { it.type } shouldBe listOf(INITIALIZED, CHILD_UPDATED)
          first[0].initialData.map { it.key to it.value.asString } shouldBe listOf("x" to "v1")
          first[1].data?.asString shouldBe "v2"
          withClue("listeners received different snapshots") {
            second[0].initialData shouldBe first[0].initialData
          }
        }
        etcd.deleteChildren(path)
      }
    }

    "an INITIALIZED listener can call rebuild() without deadlocking start()" {
      connectToEtcd(urls) { client ->
        // Not use { }: closing a deadlocked cache would block on the same monitor. A
        // deadlocked one is left to this spec's forked JVM.
        val cache = PathChildrenCache(client, "$base/rebuild-in-listener")
        val rebuilt = CountDownLatch(1)
        cache.addListener { event ->
          if (event.type == INITIALIZED) {
            cache.rebuild()
            rebuilt.countDown()
          }
        }
        thread(isDaemon = true) { runCatching { cache.start(POST_INITIALIZED_EVENT) } }
        val returned = rebuilt.await(10, TimeUnit.SECONDS)
        if (returned) cache.close()
        withClue("rebuild() inside INITIALIZED deadlocked") { returned shouldBe true }
      }
    }

    "an INITIALIZED listener can call close() without deadlocking start()" {
      connectToEtcd(urls) { client ->
        val cache = PathChildrenCache(client, "$base/close-in-listener")
        val closed = CountDownLatch(1)
        cache.addListener { event ->
          if (event.type == INITIALIZED) {
            cache.close()
            closed.countDown()
          }
        }
        val started = CountDownLatch(1)
        thread(isDaemon = true) {
          runCatching { cache.start(POST_INITIALIZED_EVENT) }
          started.countDown()
        }
        withClue("close() inside INITIALIZED deadlocked") { closed.await(10, TimeUnit.SECONDS) shouldBe true }
        withClue("start() never returned") { started.await(10, TimeUnit.SECONDS) shouldBe true }
      }
    }

    "rebuild() never resurrects a key deleted while its snapshot was in flight" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/rebuild-race"
        etcd.deleteChildren(path)
        etcd.putValue("$path/k", "v")
        val client = HookedClient(etcd)
        PathChildrenCache(client, path).use { cache ->
          val removed = CopyOnWriteArrayList<String>()
          cache.addListener { if (it.type == CHILD_REMOVED) removed += it.childName }
          cache.start(true)
          // After rebuild's snapshot is taken (with k) but before it is applied, k is
          // deleted and the watch applies the delete.
          client.afterGet.store {
            etcd.deleteKey("$path/k")
            pollUntil(10.seconds) { "k" in removed }
          }
          cache.rebuild()
          withClue("rebuild() put back a deleted key") { cache.getCurrentData("k").shouldBeNull() }
        }
        etcd.deleteChildren(path)
      }
    }

    "a throwing typed listener doesn't keep an event from the other typed listeners" {
      connectToEtcd(urls) { client ->
        val path = "$base/typed-throwing-listener"
        client.deleteChildren(path)
        TypedPathChildrenCache(client, path, IntCodec).use { cache ->
          val seen = CopyOnWriteArrayList<String>()
          cache.addListener { throw IllegalStateException("first listener fails") }
          cache.addListener { event -> seen += event.childName }
          cache.start()
          client.putValue("$path/a", "1")
          withClue("the second typed listener never saw the event") {
            pollUntil(10.seconds) { "a" in seen } shouldBe true
          }
          // The adapter rethrows after the last listener, so the failure lands just after
          pollUntil(10.seconds) { cache.untyped.exceptions.isNotEmpty() } shouldBe true
        }
        client.deleteChildren(path)
      }
    }

    "an undecodable child is left out of INITIALIZED rather than suppressing it" {
      connectToEtcd(urls) { client ->
        val path = "$base/typed-undecodable"
        client.deleteChildren(path)
        client.putValue("$path/a", "1")
        client.putValue("$path/b", "not-a-number")
        TypedPathChildrenCache(client, path, IntCodec).use { cache ->
          val initialized = CopyOnWriteArrayList<TypedPathChildrenCacheEvent<Int>>()
          cache.addListener { event -> if (event.type == INITIALIZED) initialized += event }
          cache.start(POST_INITIALIZED_EVENT)
          withClue("one undecodable child suppressed INITIALIZED") {
            pollUntil(10.seconds) { initialized.isNotEmpty() } shouldBe true
          }
          initialized.single().initialData.map { it.key to it.value } shouldBe listOf("a" to 1)
          cache.untyped.exceptions.shouldNotBeEmpty()
        }
        client.deleteChildren(path)
      }
    }
  }
}
