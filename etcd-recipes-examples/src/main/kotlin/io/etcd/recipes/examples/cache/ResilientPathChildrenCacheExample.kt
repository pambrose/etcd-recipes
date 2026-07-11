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

package io.etcd.recipes.examples.cache

import com.pambrose.common.util.sleep
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.putValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates watch recovery: run this, then kill and restart the local etcd
 * (`./etcd.sh`) while it is running. The cache keeps converging — puts made after
 * the restart show up, and every recovery event is printed.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")
  val cachePath = "/cache/resilient-example"

  connectToEtcd(urls) { client ->
    PathChildrenCache(client, cachePath).use { cache ->
      cache.addRecoveryListener { event -> logger.info { "Recovery: $event" } }
      cache.addListener { event -> logger.info { "Event: ${event.type} ${event.childName}" } }
      cache.start(true)

      repeat(120) { i ->
        client.putValue("$cachePath/tick", "value-$i")
        sleep(2.seconds)
        logger.info { "Cache now: ${cache.currentDataAsMap.mapValues { it.value.asString }}" }
      }
    }
  }
}
