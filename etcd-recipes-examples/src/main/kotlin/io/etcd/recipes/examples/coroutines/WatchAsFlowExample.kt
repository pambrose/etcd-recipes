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

package io.etcd.recipes.examples.coroutines

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.keyAsString
import io.etcd.recipes.common.valueAsString
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.coroutines.WatchFlowEvent
import io.etcd.recipes.coroutines.awaitDeleteChildren
import io.etcd.recipes.coroutines.awaitPutValue
import io.etcd.recipes.coroutines.watchAsFlow
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Consumes etcd watch events as a Flow: the collector sees puts and deletes in
 * order, resilience transitions arrive in-band, and cancelling the collector
 * closes the underlying watcher.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val prefix = "/examples/watchflow"

  runBlocking {
    connectToEtcd(urls).use { client ->
      client.awaitDeleteChildren(prefix)

      val collector =
        launch {
          client.watchAsFlow(prefix, watchOption { isPrefix(true) }).collect { element ->
            when (element) {
              is WatchFlowEvent.Response -> {
                element.response.events.forEach { event ->
                  logger.info { "${event.eventType} ${event.keyAsString} = ${event.valueAsString}" }
                }
              }

              is WatchFlowEvent.Recovery -> {
                logger.info { "Watch recovery: ${element.event}" }
              }
            }
          }
        }
      delay(500) // let the watch subscribe

      repeat(5) { i -> client.awaitPutValue("$prefix/key$i", "value$i") }
      client.awaitDeleteChildren(prefix)
      delay(1_000) // let the events arrive

      collector.cancel() // closes the watcher
      logger.info { "Collector cancelled; done" }
    }
  }
}
