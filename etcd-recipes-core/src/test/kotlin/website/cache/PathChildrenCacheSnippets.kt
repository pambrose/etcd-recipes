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

package website.cache

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.recipes.cache.ChildData
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.cache.PathChildrenCacheEvent
import io.etcd.recipes.cache.withPathChildrenCache
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.asString
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun basicPathChildrenCache(client: Client) {
  // --8<-- [start:basic]
  PathChildrenCache(client, "/cache/workers").use { cache ->
    // buildInitial = true snapshots the prefix before the watch starts, so the cache
    // is already populated by the time start() returns.
    cache.start(buildInitial = true)

    cache.currentData.forEach { child: ChildData ->
      logger.info { "${child.key} -> ${child.value.asString}" }
    }
  }
  // --8<-- [end:basic]
}

fun pathChildrenCacheStartModes(client: Client) {
  // --8<-- [start:start-modes]
  // NORMAL: no snapshot. The cache starts empty and fills only from live events.
  PathChildrenCache(client, "/cache/workers").use { cache ->
    cache.start(PathChildrenCache.StartMode.NORMAL)
  }

  // BUILD_INITIAL_CACHE: snapshot first, then watch from the snapshot revision + 1.
  PathChildrenCache(client, "/cache/workers").use { cache ->
    cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE)
  }

  // POST_INITIALIZED_EVENT: BUILD_INITIAL_CACHE, plus an INITIALIZED event carrying
  // the snapshot in event.initialData.
  PathChildrenCache(client, "/cache/workers").use { cache ->
    cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT)
  }
  // --8<-- [end:start-modes]
}

fun pathChildrenCacheWaitOnStart(client: Client) {
  // --8<-- [start:wait-on-start]
  PathChildrenCache(client, "/cache/workers").use { cache ->
    // waitOnStartComplete = false returns before the snapshot has loaded, so the
    // cache may still be empty. Useful when start() must not block your caller.
    cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE, waitOnStartComplete = false)

    // ...then bound the wait yourself. Returns false if the snapshot is still loading.
    if (cache.waitOnStartComplete(10.seconds))
      logger.info { "Primed with ${cache.currentData.size} children" }
    else
      logger.warn { "Snapshot still loading after 10s" }
  }
  // --8<-- [end:wait-on-start]
}

fun pathChildrenCacheListener(client: Client) {
  // --8<-- [start:listener]
  PathChildrenCache(client, "/cache/workers").use { cache ->
    cache.addListener { event: PathChildrenCacheEvent ->
      when (event.type) {
        PathChildrenCacheEvent.Type.CHILD_ADDED -> logger.info { "Added ${event.childName}" }

        PathChildrenCacheEvent.Type.CHILD_UPDATED -> logger.info { "Updated ${event.childName}" }

        // data carries the value the child held immediately before removal.
        PathChildrenCacheEvent.Type.CHILD_REMOVED -> logger.info { "Removed ${event.childName}" }

        // Only INITIALIZED events populate initialData; it is empty on the others.
        PathChildrenCacheEvent.Type.INITIALIZED -> logger.info { "Primed: ${event.initialData.size}" }
      }
    }
    cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT)
  }
  // --8<-- [end:listener]
}

fun pathChildrenCacheReads(client: Client) {
  // --8<-- [start:reads]
  PathChildrenCache(client, "/cache/workers").use { cache ->
    cache.start(buildInitial = true)

    // Sorted by child name, so iteration order is stable across clients.
    cache.currentData.forEach { logger.info { "${it.key} -> ${it.value.asString}" } }

    // getCurrentData() takes the child name RELATIVE to the cache path, not a full
    // path: "/cache/workers/w1" returns null, "w1" is what you want.
    val w1: ByteSequence? = cache.getCurrentData("w1")
    logger.info { "w1 -> ${w1?.asString}" }

    // A point-in-time copy of the whole prefix.
    val snapshot: Map<String, ByteSequence> = cache.currentDataAsMap
    logger.info { "Holding ${snapshot.size} children" }
  }
  // --8<-- [end:reads]
}

fun pathChildrenCacheRebuild(client: Client) {
  // --8<-- [start:rebuild]
  PathChildrenCache(client, "/cache/workers").use { cache ->
    cache.start(buildInitial = true)

    // A coarse manual re-sync against etcd's current children. The watcher already
    // keeps the cache converged, so this is a repair tool, not part of steady state:
    // a live watch event on the same key can race the snapshot, last writer wins.
    cache.rebuild()

    // Drops every entry locally without touching etcd. The next event or rebuild()
    // refills it; until then currentData reports nothing.
    cache.clear()
  }
  // --8<-- [end:rebuild]
}

fun pathChildrenCacheRecovery(client: Client) {
  // --8<-- [start:recovery]
  PathChildrenCache(client, "/cache/workers").use { cache ->
    cache.addRecoveryListener { event: WatchRecoveryEvent ->
      when (event) {
        is WatchRecoveryEvent.Suspended -> logger.warn { "Watch stream died; retrying" }

        is WatchRecoveryEvent.Resubscribed -> logger.info { "Watch re-established" }

        is WatchRecoveryEvent.Resynced -> logger.info { "Compaction: cache re-synced" }

        // Recovery abandoned: the cache is frozen and will never update again.
        is WatchRecoveryEvent.Failed -> logger.error { "Watch abandoned; cache is stale" }
      }
    }
    cache.start(buildInitial = true)
  }
  // --8<-- [end:recovery]
}

fun pathChildrenCacheResilience(client: Client) {
  // --8<-- [start:resilience]
  // withPathChildrenCache does not expose resilience; construct directly for that.
  PathChildrenCache(
    client = client,
    cachePath = "/cache/workers",
    userExecutor = null,
    resilience = ResilienceConfig.DEFAULT,
  ).use { cache ->
    cache.start(buildInitial = true)
    logger.info { "Children: ${cache.currentData.size}" }
  }
  // --8<-- [end:resilience]
}

fun scopedPathChildrenCache(client: Client) {
  // --8<-- [start:scoped]
  val names: List<String> =
    withPathChildrenCache(client, "/cache/workers") {
      start(buildInitial = true)
      currentData.map { it.key }
    }
  logger.info { "Workers: $names" }
  // --8<-- [end:scoped]
}
