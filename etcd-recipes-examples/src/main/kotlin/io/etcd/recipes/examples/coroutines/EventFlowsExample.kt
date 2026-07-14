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

import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.putValue
import io.etcd.recipes.coroutines.connectionStateAsFlow
import io.etcd.recipes.coroutines.eventsAsFlow
import io.etcd.recipes.coroutines.leadershipAsFlow
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Composes several event flows in one structured-concurrency scope: cache child
 * events, the recipe's connection state, and leadership hand-offs are each collected
 * in their own coroutine, and cancelling the scope unsubscribes them all.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")
  val cachePath = "/examples/coroutines/flows/cache"
  val electionPath = "/examples/coroutines/flows/election"

  runBlocking {
    connectToEtcd(urls).use { client ->
      PathChildrenCache(client, cachePath).use { cache ->
        cache.start(buildInitial = true)
        cache.waitOnStartComplete()

        coroutineScope {
          val collectors =
            launch {
              launch {
                cache.eventsAsFlow().collect { event ->
                  logger.info { "Cache: ${event.type} ${event.childName}" }
                }
              }
              launch {
                cache.connectionStateAsFlow().collect { state ->
                  logger.info { "Connection state: $state" }
                }
              }
              launch {
                client.leadershipAsFlow(electionPath).collect { event ->
                  logger.info { "Leadership: $event" }
                }
              }
            }
          delay(500) // let the collectors subscribe

          client.putValue("$cachePath/config", "v1")
          client.putValue("$cachePath/config", "v2")
          delay(1_000) // let the events arrive

          collectors.cancel() // unsubscribes every flow
        }
      }
    }
    logger.info { "Done" }
  }
}
