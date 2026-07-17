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

import io.etcd.jetcd.options.WatchOption
import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.compact
import io.etcd.recipes.common.compactOption
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.keyAsString
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.common.valueAsString
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the resilient watcher through real faults on a private Testcontainers etcd:
 * process restart, pause/unpause (partition), and compaction of the watched revision.
 */
class ResilientWatcherFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "watcher keeps receiving events across an etcd restart" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val prefix = "$path/restart"
        val seen = CopyOnWriteArrayList<String>()
        client.watcher(prefix, watchOption { isPrefix(true) }) { resp ->
          resp.events.forEach { seen += it.valueAsString }
        }.use {
          client.putValue("$prefix/a", "before")
          pollUntil(10.seconds) { seen.contains("before") } shouldBe true

          EtcdTestContainer.restart()

          client.putValue("$prefix/b", "after")
          pollUntil(30.seconds) { seen.contains("after") } shouldBe true
        }
      }
    }

    "watcher survives a paused etcd and delivers events after unpause" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val prefix = "$path/pause"
        val seen = CopyOnWriteArrayList<String>()
        client.watcher(prefix, watchOption { isPrefix(true) }) { resp ->
          resp.events.forEach { seen += it.valueAsString }
        }.use {
          client.putValue("$prefix/a", "before")
          pollUntil(10.seconds) { seen.contains("before") } shouldBe true

          EtcdTestContainer.pause()
          try {
            Thread.sleep(3_000)
          } finally {
            EtcdTestContainer.unpause()
          }
          EtcdTestContainer.awaitReady()

          client.putValue("$prefix/b", "after")
          pollUntil(30.seconds) { seen.contains("after") } shouldBe true
        }
      }
    }

    "watcher anchored at a compacted revision resyncs and emits Resynced" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
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
        // anchor below the compaction point: etcd kills this watch with ErrCompacted
        val anchored = watchOption { isPrefix(true).withRevision(oldRevision) }
        client.watcher(prefix, anchored, WatchResilience.DEFAULT, { recovery += it }, resyncWith) { resp ->
          resp.events.forEach { seen += it.keyAsString to it.valueAsString }
        }.use {
          pollUntil(30.seconds) { recovery.any { it is WatchRecoveryEvent.Resynced } } shouldBe true
          val resync = recovery.filterIsInstance<WatchRecoveryEvent.Resynced>().first()
          (resync.compactRevision > 0L) shouldBe true
          (resync.anchorRevision > resync.compactRevision) shouldBe true
          // the resync GET observed the current state despite the lost history
          pollUntil(10.seconds) { seen.any { it.first == key && it.second == "v3" } } shouldBe true

          // and the re-anchored watch is live for new events
          client.putValue(key, "v4")
          pollUntil(30.seconds) { seen.any { it.second == "v4" } } shouldBe true
        }
      }
    }
  }
}
