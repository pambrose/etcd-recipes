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
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.cache.PathChildrenCacheEvent
import io.etcd.recipes.cache.TypedChildData
import io.etcd.recipes.cache.TypedPathChildrenCache
import io.etcd.recipes.cache.withTypedPathChildrenCache
import io.etcd.recipes.common.jsonCodec
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class Worker(
  val name: String,
  val load: Int,
)

fun basicTypedCache(client: Client) {
  // --8<-- [start:basic]
  TypedPathChildrenCache(client, "/cache/workers", jsonCodec<Worker>()).use { cache ->
    cache.start(buildInitial = true)

    cache.currentData.forEach { child: TypedChildData<Worker> ->
      logger.info { "${child.key} -> ${child.value.name} (load ${child.value.load})" }
    }

    // Same relative-child-name rule as the untyped cache, decoded on the way out.
    val w1: Worker? = cache.getCurrentData("w1")
    logger.info { "w1 -> $w1" }
  }
  // --8<-- [end:basic]
}

fun typedCacheListener(client: Client) {
  // --8<-- [start:listener]
  TypedPathChildrenCache(client, "/cache/workers", jsonCodec<Worker>()).use { cache ->
    // The event is a TypedPathChildrenCacheEvent<Worker>, but its `type` is the
    // untyped PathChildrenCacheEvent.Type: there is no separate typed enum.
    cache.addListener { event ->
      when (event.type) {
        PathChildrenCacheEvent.Type.CHILD_ADDED -> logger.info { "Added ${event.data?.name}" }
        PathChildrenCacheEvent.Type.CHILD_UPDATED -> logger.info { "Updated ${event.data?.name}" }
        PathChildrenCacheEvent.Type.CHILD_REMOVED -> logger.info { "Removed ${event.childName}" }
        PathChildrenCacheEvent.Type.INITIALIZED -> logger.info { "Primed: ${event.initialData.size}" }
      }
    }
    cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT)
  }
  // --8<-- [end:listener]
}

fun typedCacheUntypedEscapeHatch(client: Client) {
  // --8<-- [start:untyped]
  TypedPathChildrenCache(client, "/cache/workers", jsonCodec<Worker>()).use { cache ->
    cache.start(buildInitial = true)

    // TypedPathChildrenCache is a Closeable decorator, NOT an EtcdConnector. The
    // connector surface lives on `untyped`, which is the documented escape hatch.
    val untyped: PathChildrenCache = cache.untyped

    // A payload that fails to decode is recorded here and its event skipped, rather
    // than killing the watch dispatcher. Nothing else tells you it happened.
    untyped.exceptions.forEach { logger.warn(it) { "Cache background failure" } }
    logger.info { "Healthy: ${untyped.isHealthy()}, state: ${untyped.connectionState}" }

    // Raw, undecoded views of the same cache are reachable through it too.
    logger.info { "Raw children: ${untyped.currentDataAsMap.keys}" }
  }
  // --8<-- [end:untyped]
}

fun scopedTypedCache(client: Client) {
  // --8<-- [start:scoped]
  val loads: Map<String, Int> =
    withTypedPathChildrenCache(client, "/cache/workers", jsonCodec<Worker>()) {
      start(buildInitial = true)
      currentDataAsMap.mapValues { (_, worker) -> worker.load }
    }
  logger.info { "Loads: $loads" }
  // --8<-- [end:scoped]
}
