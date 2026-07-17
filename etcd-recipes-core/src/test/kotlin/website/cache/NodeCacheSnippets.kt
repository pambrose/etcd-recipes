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

import io.etcd.jetcd.Client
import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.cache.NodeCacheEvent
import io.etcd.recipes.cache.withNodeCache
import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.jsonCodec
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class FeatureFlags(
  val darkMode: Boolean,
  val maxBatchSize: Int,
)

fun basicNodeCache(client: Client) {
  // --8<-- [start:basic]
  NodeCache(client, "/config/greeting", StringCodec).use { cache ->
    // start() snapshots the key, then anchors the watch at the snapshot revision + 1.
    cache.start()

    // Reads are local: no round trip, and no chance of blocking on a slow etcd.
    logger.info { "Current value: ${cache.current}" }
  }
  // --8<-- [end:basic]
}

fun nodeCacheListener(client: Client) {
  // --8<-- [start:listener]
  NodeCache(client, "/config/greeting", StringCodec).use { cache ->
    // Register listeners before start(): the watch begins delivering the moment
    // start() returns, and nothing replays what you were not listening for.
    cache.addListener { event: NodeCacheEvent<String> ->
      when (event.type) {
        NodeCacheEvent.Type.CREATED -> logger.info { "Created: ${event.value}" }

        NodeCacheEvent.Type.UPDATED -> logger.info { "Updated: ${event.value}" }

        // value is null on DELETED: the key is gone.
        NodeCacheEvent.Type.DELETED -> logger.info { "Deleted" }
      }
    }
    cache.start()
  }
  // --8<-- [end:listener]
}

fun typedNodeCache(client: Client) {
  // --8<-- [start:typed]
  // Any EtcdCodec<T> works: StringCodec, ByteSequenceCodec, jsonCodec<T>(), or the
  // Jackson codec from etcd-recipes-jackson.
  NodeCache(client, "/config/flags", jsonCodec<FeatureFlags>()).use { cache ->
    cache.start()

    // current decodes on read, so a malformed payload throws here, at your call site.
    val flags: FeatureFlags? = cache.current
    logger.info { "Max batch size: ${flags?.maxBatchSize}" }

    // currentBytes hands back the raw value and skips the codec entirely.
    logger.info { "Raw: ${cache.currentBytes}" }
  }
  // --8<-- [end:typed]
}

fun nodeCacheRecovery(client: Client) {
  // --8<-- [start:recovery]
  NodeCache(client, "/config/greeting", StringCodec).use { cache ->
    cache.addRecoveryListener { event: WatchRecoveryEvent ->
      when (event) {
        is WatchRecoveryEvent.Suspended -> logger.warn { "Watch stream died; retrying" }

        is WatchRecoveryEvent.Resubscribed -> logger.info { "Watch re-established" }

        is WatchRecoveryEvent.Resynced -> logger.info { "Compaction: re-snapshotted the key" }

        // Recovery abandoned: current is frozen at whatever it last saw.
        is WatchRecoveryEvent.Failed -> logger.error { "Watch abandoned; value is stale" }
      }
    }
    cache.start()
  }
  // --8<-- [end:recovery]
}

fun scopedNodeCache(client: Client) {
  // --8<-- [start:scoped]
  val greeting: String? =
    withNodeCache(client, "/config/greeting", StringCodec) {
      start()
      current
    }
  logger.info { "Greeting: $greeting" }
  // --8<-- [end:scoped]
}
