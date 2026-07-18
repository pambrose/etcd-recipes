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

package io.etcd.recipes.examples.cache

import com.pambrose.common.util.sleep
import io.etcd.recipes.cache.PathChildrenCacheEvent
import io.etcd.recipes.cache.withPathChildrenCache
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.putValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val cachePath = "/cache/test"

  connectToEtcd(urls) { client ->

    client.putValue("$cachePath/child1", "a child 1 value")
    client.putValue("$cachePath/child2", "a child 2 value")

    sleep(1.seconds)

    withPathChildrenCache(client, cachePath) {
      addListener { event: PathChildrenCacheEvent ->
        logger.info { "CB: ${event.type} ${event.childName} ${event.data?.asString}" }
      }

      start(true)
      waitOnStartComplete()

      currentData.forEach { logger.info { "${it.key} ${it.value.asString}" } }

      logger.info { "Deleted: ${client.deleteChildren(cachePath)}" }

      sleep(1.seconds)
    }
  }
}
