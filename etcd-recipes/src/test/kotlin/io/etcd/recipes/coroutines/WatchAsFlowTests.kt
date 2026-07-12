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

import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.keyAsString
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.common.valueAsString
import io.etcd.recipes.common.watchOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Flow-based watch semantics: ordered delivery, flattening, cancellation closing the
 * watcher, collector independence, and lossless buffering under a slow collector.
 */
class WatchAsFlowTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  private suspend fun untilTrue(
    timeout: Duration,
    predicate: () -> Boolean,
  ): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < timeout) {
      if (predicate()) return true
      delay(50)
    }
    return predicate()
  }

  // Collectors run on their own scope: the kotest test context must stay free to
  // drive puts and assertions while collection is live. The settle delay lets the
  // watch subscription go live before the test starts writing.
  private suspend fun <T> withCollector(
    collect: suspend CoroutineScope.() -> Job,
    body: suspend (Job) -> T,
  ): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val job = collect(scope)
      delay(1_000)
      return body(job)
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }

  init {
    "watchAsFlow delivers responses in order" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val key = "$base/ordered/key"
        val seen = CopyOnWriteArrayList<Pair<WatchEvent.EventType, String>>()

        withCollector(
          collect = {
            launch {
              client.watchAsFlow(key).filterIsInstance<WatchFlowEvent.Response>().collect { r ->
                r.response.events.forEach { seen += it.eventType to it.valueAsString }
              }
            }
          },
        ) {
          client.putValue(key, "v1")
          client.putValue(key, "v2")
          client.deleteChildren("$base/ordered")
          untilTrue(15.seconds) { seen.size == 3 } shouldBe true
          seen.map { it.first } shouldContainExactly
            listOf(WatchEvent.EventType.PUT, WatchEvent.EventType.PUT, WatchEvent.EventType.DELETE)
          seen.take(2).map { it.second } shouldContainExactly listOf("v1", "v2")
        }
      }
    }

    "watchEventsAsFlow flattens responses to events across a prefix" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val prefix = "$base/flat"
        val seen = CopyOnWriteArrayList<String>()

        withCollector(
          collect = {
            launch {
              client.watchEventsAsFlow(prefix, watchOption { isPrefix(true) }).collect { event ->
                seen += event.keyAsString.substringAfterLast('/')
              }
            }
          },
        ) {
          client.putValue("$prefix/a", "1")
          client.putValue("$prefix/b", "2")
          client.putValue("$prefix/c", "3")
          untilTrue(15.seconds) { seen.size == 3 } shouldBe true
          seen.toList() shouldContainExactly listOf("a", "b", "c")
        }
      }
    }

    "cancelling the collector closes the watcher" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val key = "$base/cancel/key"
        val seen = CopyOnWriteArrayList<String>()

        withCollector(
          collect = {
            launch {
              client.watchEventsAsFlow(key).collect { seen += it.valueAsString }
            }
          },
        ) { job ->
          client.putValue(key, "before")
          untilTrue(15.seconds) { seen.size == 1 } shouldBe true

          job.cancelAndJoin()

          client.putValue(key, "after")
          delay(2_000) // grace: a live watcher would have delivered by now
          seen.toList() shouldContainExactly listOf("before")
        }
      }
    }

    "two collectors get independent watchers" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val key = "$base/dual/key"
        val seenA = CopyOnWriteArrayList<String>()
        val seenB = CopyOnWriteArrayList<String>()

        withCollector(
          collect = {
            launch { client.watchEventsAsFlow(key).collect { seenA += it.valueAsString } }
            launch { client.watchEventsAsFlow(key).collect { seenB += it.valueAsString } }
          },
        ) {
          client.putValue(key, "both")
          untilTrue(15.seconds) { seenA.size == 1 && seenB.size == 1 } shouldBe true
          seenA.single() shouldBe "both"
          seenB.single() shouldBe "both"
        }
      }
    }

    "a slow collector loses nothing under the unlimited default" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val prefix = "$base/slow"
        val seen = CopyOnWriteArrayList<String>()

        withCollector(
          collect = {
            launch {
              client.watchEventsAsFlow(prefix, watchOption { isPrefix(true) }).collect {
                delay(50) // slower than the producer
                seen += it.valueAsString
              }
            }
          },
        ) {
          repeat(20) { i -> client.putValue("$prefix/k$i", "v$i") }
          untilTrue(30.seconds) { seen.size == 20 } shouldBe true
          seen.toList() shouldContainExactly (0 until 20).map { "v$it" }
        }
      }
    }
  }
}
