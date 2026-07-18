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
import io.etcd.recipes.cache.TypedPathChildrenCache
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.common.putValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watch a prefix of typed values with a [TypedPathChildrenCache]: `currentData` yields decoded
 * [Worker]s and a listener is notified with the decoded payload on each child change.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val path = "/cache/typed-example"
  val codec = jsonCodec<Worker>()

  connectToEtcd(urls) { client ->
    client.deleteChildren(path)
    client.putValue("$path/w1", Worker("alpha", 3), codec)

    TypedPathChildrenCache(client, path, codec).use { cache ->
      cache.addListener { event -> logger.info { "Change: ${event.type} ${event.childName} -> ${event.data}" } }
      cache.start(buildInitial = true)
      logger.info { "Initial: ${cache.currentData}" }

      client.putValue("$path/w2", Worker("beta", 7), codec)
      sleep(500.milliseconds)
      logger.info { "Now: ${cache.currentData}" }
    }
  }
}
