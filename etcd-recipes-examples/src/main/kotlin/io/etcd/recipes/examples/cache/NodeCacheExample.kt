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
import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.putValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keep a single config key hot with a [NodeCache]: `current` tracks the live value and a
 * listener is notified of each CREATED / UPDATED / DELETED change. Uses [StringCodec];
 * swap in `jsonCodec<T>()` for a typed payload.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")
  val key = "/config/node-example/greeting"

  connectToEtcd(urls) { client ->
    client.putValue(key, "hello")

    NodeCache(client, key, StringCodec).use { cache ->
      cache.addListener { event -> logger.info { "Change: ${event.type} -> ${event.value}" } }
      cache.start()
      logger.info { "Initial value: ${cache.current}" }

      client.putValue(key, "world")
      sleep(500.milliseconds)
      logger.info { "After update: ${cache.current}" }

      client.deleteKey(key)
      sleep(500.milliseconds)
      logger.info { "After delete: ${cache.current}" }
    }
  }
}
