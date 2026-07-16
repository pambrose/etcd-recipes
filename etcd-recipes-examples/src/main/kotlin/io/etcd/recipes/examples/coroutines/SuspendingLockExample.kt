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
import io.etcd.recipes.coroutines.withLock
import io.etcd.recipes.coroutines.withPermit
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedSemaphore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Coroutine-scoped mutual exclusion and concurrency limiting: [withLock] holds a
 * mutex across a suspend section (acquisition and release confined to one thread),
 * and [withPermit] caps how many coroutines run a section at once.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")

  runBlocking {
    connectToEtcd(urls).use { client ->
      DistributedMutex(client, "/examples/coroutines/mutex").use { mutex ->
        val counter = AtomicInt(0)
        coroutineScope {
          repeat(5) { id ->
            launch(Dispatchers.Default) {
              mutex.withLock {
                val n = counter.incrementAndFetch()
                logger.info { "Worker $id holds the mutex (count=$n)" }
                delay(100)
              }
            }
          }
        }
      }

      DistributedSemaphore(client, "/examples/coroutines/semaphore", permits = 2).use { semaphore ->
        val inFlight = AtomicInt(0)
        coroutineScope {
          repeat(6) { id ->
            launch(Dispatchers.Default) {
              semaphore.withPermit {
                val now = inFlight.incrementAndFetch()
                logger.info { "Worker $id holds a permit ($now of 2 in flight)" }
                delay(150)
                inFlight.decrementAndFetch()
              }
            }
          }
        }
      }
    }
  }
}
