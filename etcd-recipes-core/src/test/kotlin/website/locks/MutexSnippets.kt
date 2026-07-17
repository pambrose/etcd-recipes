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

package website.locks

import io.etcd.jetcd.Client
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun basicMutex(client: Client) {
  // --8<-- [start:basic]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    mutex.withLock {
      // Only one client across the cluster runs this at a time.
      logger.info { "Critical section" }
    }
  }
  // --8<-- [end:basic]
}

fun tryLockMutex(client: Client) {
  // --8<-- [start:try-lock]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    if (mutex.tryLock(5.seconds)) {
      try {
        logger.info { "Acquired within the timeout" }
      } finally {
        mutex.unlock()
      }
    } else {
      logger.info { "Someone else holds the lock; moving on" }
    }
  }
  // --8<-- [end:try-lock]
}

fun mutexLockLost(client: Client) {
  // --8<-- [start:lock-lost]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    // The acquisition lease is deliberately never healed. If it expires, etcd has
    // already promoted the next waiter, so the hold is gone and cannot be reclaimed.
    mutex.addLockLostListener { cause ->
      logger.warn { "Lost the lock, abandoning work: $cause" }
    }

    mutex.withLock {
      // unlock() returns false if the hold was lost while inside the section.
      logger.info { "Working" }
    }
  }
  // --8<-- [end:lock-lost]
}

fun mutexInterruptOnLoss(client: Client) {
  // --8<-- [start:interrupt-on-loss]
  // Opt in to having the holding thread interrupted the moment the lock is lost,
  // rather than letting it run on against state it no longer owns.
  DistributedMutex(
    client = client,
    lockPath = "/locks/orders",
    leaseTtlSecs = 5L,
    interruptOnLockLoss = true,
  ).use { mutex ->
    mutex.withLock {
      logger.info { "Interrupted if the lease expires under us" }
    }
  }
  // --8<-- [end:interrupt-on-loss]
}

fun mutexReentrant(client: Client) {
  // --8<-- [start:reentrant]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    mutex.withLock {
      mutex.withLock {
        // Reentrant within the same thread; holdCount is now 2.
        logger.info { "holdCount=${mutex.holdCount}" }
      }
    }
  }
  // --8<-- [end:reentrant]
}
