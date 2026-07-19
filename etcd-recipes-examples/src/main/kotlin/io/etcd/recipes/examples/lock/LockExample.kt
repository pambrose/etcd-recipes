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
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.thread

/**
 * Demonstrates mutual exclusion: five workers increment an unguarded counter
 * 100 times each under the mutex — the total is exact because etcd's native
 * lock service serializes them (FIFO). Lock-lost events are printed.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val lockPath = "/locks/example"
  var unguarded = 0

  val workers =
    (1..5).map { worker ->
      thread {
        connectToEtcd(urls) { client ->
          DistributedMutex(client, lockPath).use { mutex ->
            mutex.addLockLostListener { cause -> logger.warn { "Worker $worker lost the lock: $cause" } }
            repeat(100) {
              mutex.withLock { unguarded += 1 }
            }
          }
        }
      }
    }
  workers.forEach { it.join() }
  logger.info { "Final count: $unguarded (expected 500)" }
}
