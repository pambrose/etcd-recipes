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

package io.etcd.recipes.examples.basics

import io.etcd.jetcd.watch.WatchResponse
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val path = "/foo"
  val keyval = "foobar"
  val latch = CountDownLatch(2)

  thread {
    try {
      Thread.sleep(3_000)
      connectToEtcd(urls) { client ->
        repeat(10) { i ->
          if (i > 0) Thread.sleep(1_000)
          val kv = keyval + i
          logger.info { "Assigning $path = $kv" }
          client.putValue(path, kv)
          Thread.sleep(2_000)
          logger.info { "Deleting $path" }
          client.deleteKey(path)
        }
      }
    } finally {
      latch.countDown()
    }
  }

  thread {
    try {
      connectToEtcd(urls) { client ->
        client.withWatcher(
          path,
          block = { watchResponse: WatchResponse ->
            for (event in watchResponse.events) {
              logger.info { "Watch event: ${event.eventType} ${event.keyValue.asString}" }
            }
          },
        ) {
          logger.info { "Started watch" }
          Thread.sleep(10_000)
          logger.info { "Closing watch" }
        }
        logger.info { "Closed watch" }
      }
    } finally {
      latch.countDown()
    }
  }

  latch.await()
}
