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
import io.etcd.recipes.lock.DistributedReadWriteLock
import io.etcd.recipes.lock.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.thread

/**
 * Demonstrates the fair read-write lock: readers share the lock concurrently,
 * a writer excludes everyone, and arrivals are honored in order — a queued
 * writer is never starved by later readers.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")
  val lockPath = "/locks/rw-example"
  var document = "v0"

  val workers =
    (1..6).map { worker ->
      thread {
        connectToEtcd(urls) { client ->
          DistributedReadWriteLock(client, lockPath).use { rw ->
            if (worker % 3 == 0) {
              rw.writeLock.withLock {
                document = "v$worker"
                logger.info { "Writer $worker updated the document to $document" }
                Thread.sleep(300)
              }
            } else {
              rw.readLock.withLock {
                logger.info { "Reader $worker sees $document (shared with other readers)" }
                Thread.sleep(300)
              }
            }
          }
        }
      }
    }
  workers.forEach { it.join() }
  logger.info { "Final document: $document" }
}
