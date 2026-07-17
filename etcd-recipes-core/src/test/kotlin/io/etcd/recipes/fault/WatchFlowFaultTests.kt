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

import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.compact
import io.etcd.recipes.common.compactOption
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.keyAsString
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.common.valueAsString
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.coroutines.WatchFlowEvent
import io.etcd.recipes.coroutines.watchAsFlow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Real faults through the Flow watch surface: recovery transitions arrive in-band
 * as [WatchFlowEvent.Recovery] and the flow keeps delivering afterwards.
 */
class WatchFlowFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

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

  init {
    "a flow anchored at a compacted revision resyncs in-band and stays live" {
      assumeFaultInjection()
      connectToEtcd(urls).use { client ->
        client.deleteChildren(path)
        val prefix = "$path/compacted"
        val key = "$prefix/key"

        client.putValue(key, "v1")
        val oldRevision = client.getResponse(key).kvs.first().modRevision
        client.putValue(key, "v2")
        client.putValue(key, "v3")
        val headRevision = client.getResponse(key).header.revision
        client.compact(headRevision, compactOption { withCompactPhysical(true) })

        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        val recovery = CopyOnWriteArrayList<WatchRecoveryEvent>()
        val resyncWith = {
          val resp = client.getResponse(prefix, getOption { isPrefix(true) })
          resp.kvs.forEach { kv -> seen += kv.key.asString to kv.value.asString }
          resp.header.revision + 1
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
          scope.launch {
            // anchored below the compaction point: etcd kills this watch with ErrCompacted
            client
              .watchAsFlow(
                prefix,
                watchOption { isPrefix(true).withRevision(oldRevision) },
                resyncWith = resyncWith,
              ).collect { element ->
                when (element) {
                  is WatchFlowEvent.Response -> {
                    element.response.events.forEach { seen += it.keyAsString to it.valueAsString }
                  }

                  is WatchFlowEvent.Recovery -> {
                    recovery += element.event
                  }
                }
              }
          }

          untilTrue(30.seconds) { recovery.any { it is WatchRecoveryEvent.Resynced } } shouldBe true
          val resync = recovery.filterIsInstance<WatchRecoveryEvent.Resynced>().first()
          (resync.compactRevision > 0L) shouldBe true
          (resync.anchorRevision > resync.compactRevision) shouldBe true
          // the resync GET observed the current state despite the lost history
          untilTrue(10.seconds) { seen.any { it.first == key && it.second == "v3" } } shouldBe true

          // and the re-anchored flow is live for new events
          client.putValue(key, "v4")
          untilTrue(30.seconds) { seen.any { it.second == "v4" } } shouldBe true
        } finally {
          scope.coroutineContext[Job]!!.cancelAndJoin()
        }
      }
    }

    "a flow survives a paused etcd and delivers events after unpause" {
      assumeFaultInjection()
      connectToEtcd(urls).use { client ->
        client.deleteChildren(path)
        val prefix = "$path/pause"
        val seen = CopyOnWriteArrayList<String>()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
          scope.launch {
            client.watchAsFlow(prefix, watchOption { isPrefix(true) }).collect { element ->
              if (element is WatchFlowEvent.Response) {
                element.response.events.forEach { seen += it.valueAsString }
              }
            }
          }
          delay(1_000)

          client.putValue("$prefix/a", "before")
          untilTrue(10.seconds) { seen.contains("before") } shouldBe true

          EtcdTestContainer.pause()
          try {
            delay(3_000)
          } finally {
            EtcdTestContainer.unpause()
          }
          EtcdTestContainer.awaitReady()

          client.putValue("$prefix/b", "after")
          untilTrue(30.seconds) { seen.contains("after") } shouldBe true
        } finally {
          scope.coroutineContext[Job]!!.cancelAndJoin()
        }
      }
    }
  }
}
