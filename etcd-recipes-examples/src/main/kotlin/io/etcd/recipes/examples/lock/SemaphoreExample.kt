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

package io.etcd.recipes.examples.lock

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.lock.withPermit
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread

/**
 * Demonstrates the counting semaphore: six workers contend for two permits, so
 * at most two are ever inside the guarded section at once, admitted in arrival
 * order.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")
  val semaphorePath = "/semaphores/example"
  val inFlight = AtomicInt(0)

  val workers =
    (1..6).map { worker ->
      thread {
        connectToEtcd(urls) { client ->
          DistributedSemaphore(client, semaphorePath, permits = 2).use { semaphore ->
            semaphore.withPermit {
              val now = inFlight.incrementAndFetch()
              logger.info { "Worker $worker holds a permit ($now of 2 in flight)" }
              Thread.sleep(500)
              inFlight.decrementAndFetch()
            }
          }
        }
      }
    }
  workers.forEach { it.join() }
  logger.info { "All workers finished" }
}
