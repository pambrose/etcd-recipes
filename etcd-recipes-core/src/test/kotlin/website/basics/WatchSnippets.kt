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

package website.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.keyAsString
import io.etcd.recipes.common.valueAsString
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.etcd.recipes.common.watcherWithLatch
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch

private val logger = KotlinLogging.logger {}

fun basicWatch(client: Client) {
  // --8<-- [start:basic]
  // watcher returns a Closeable Watch.Watcher: the watch lives until you close it.
  client
    .watcher("/config/name") { response ->
      for (event in response.events) {
        logger.info { "${event.eventType} ${event.keyAsString} = ${event.valueAsString}" }
      }
    }.use { watcher ->
      logger.info { "Watching /config/name; closed=${watcher.isClosed}" }
    }
  // --8<-- [end:basic]
}

fun scopedWatch(client: Client) {
  // --8<-- [start:with-watcher]
  // withWatcher binds the watch's lifetime to the receiver block and closes it on exit,
  // including on an exception. Prefer it over watcher() + a hand-written finally.
  val seen = CountDownLatch(3)
  client.withWatcher(
    "/config/name",
    block = { response -> response.events.forEach { seen.countDown() } },
  ) {
    seen.await()
  }
  // --8<-- [end:with-watcher]
}

fun watchPrefix(client: Client) {
  // --8<-- [start:watch-prefix]
  // watchOption { } builds a jetcd WatchOption. isPrefix(true) turns a single-key watch
  // into a range watch over every key beneath the prefix — one stream, not one per key.
  val option = watchOption { isPrefix(true) }

  client.withWatcher(
    "/services/",
    option,
    block = { response ->
      for (event in response.events) {
        logger.info { "${event.eventType} ${event.keyAsString}" }
      }
    },
  ) {
    logger.info { "Watching everything under /services/" }
  }
  // --8<-- [end:watch-prefix]
}

fun watchWithLatch(
  client: Client,
  endWatch: CountDownLatch,
) {
  // --8<-- [start:watcher-with-latch]
  // Splits PUT from DELETE for you and blocks the calling thread until the latch drops.
  // UNRECOGNIZED event types are ignored rather than surfaced.
  client.watcherWithLatch(
    "/services/",
    endWatch,
    onPut = { event -> logger.info { "Put: ${event.keyAsString}" } },
    onDelete = { event -> logger.info { "Deleted: ${event.keyAsString}" } },
    option = watchOption { isPrefix(true) },
  )
  // --8<-- [end:watcher-with-latch]
}

fun resilientWatch(client: Client) {
  // --8<-- [start:resilient]
  client
    .watcher(
      keyName = "/services/",
      option = watchOption { isPrefix(true) },
      // jetcd retries transient stream errors itself. This recovers what it gives up
      // on: compaction, halt statuses, and "etcdserver: no leader".
      resilience = WatchResilience.DEFAULT,
      recoveryListener = { event ->
        when (event) {
          is WatchRecoveryEvent.Suspended -> logger.warn { "Stream died: ${event.cause}" }
          is WatchRecoveryEvent.Resubscribed -> logger.info { "Resumed at ${event.resumeRevision}" }
          is WatchRecoveryEvent.Resynced -> logger.warn { "Gap: ${event.compactRevision}..${event.anchorRevision}" }
          is WatchRecoveryEvent.Failed -> logger.error { "Watcher abandoned: ${event.cause}" }
        }
      },
      resyncWith = {
        // Called only after a compaction, on the dispatcher thread. Events in the gap are
        // gone for good, so re-read the world, rebuild derived state, and return the
        // revision to re-anchor at.
        val response = client.getResponse("/services/", getOption { isPrefix(true) })
        rebuildStateFrom(response.kvs)
        response.header.revision + 1
      },
      block = { response -> response.events.forEach { logger.info { it.keyAsString } } },
    ).use {
      logger.info { "Watching with recovery" }
    }
  // --8<-- [end:resilient]
}

fun offloadFromCallback(
  client: Client,
  work: BlockingQueue<String>,
) {
  // --8<-- [start:callback-offload]
  // The callback runs on one dedicated dispatcher thread, not a pool: until it returns,
  // no further event for this watcher is delivered. Keep it a pure handoff and let a
  // thread you own do the etcd calls and the lock taking.
  client
    .watcher("/services/") { response ->
      response.events.forEach { event -> work.put(event.keyAsString) }
    }.use {
      logger.info { "Callback only enqueues; a worker drains the queue" }
    }
  // --8<-- [end:callback-offload]
}

private fun rebuildStateFrom(kvs: List<KeyValue>) {
  logger.info { "Rebuilt derived state from ${kvs.size} keys" }
}
