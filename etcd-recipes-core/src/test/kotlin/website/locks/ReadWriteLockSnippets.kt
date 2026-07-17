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
import io.etcd.recipes.lock.DistributedReadWriteLock
import io.etcd.recipes.lock.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun basicReadWriteLock(client: Client) {
  // --8<-- [start:basic]
  DistributedReadWriteLock(client, "/locks/catalog").use { rwLock ->
    // Any number of readers may hold the read lock at once...
    rwLock.readLock.withLock {
      logger.info { "Reading the catalog" }
    }

    // ...but a writer excludes every reader and every other writer.
    rwLock.writeLock.withLock {
      logger.info { "Rewriting the catalog" }
    }
  }
  // --8<-- [end:basic]
}

fun readWriteLockTimed(client: Client) {
  // --8<-- [start:try-lock]
  DistributedReadWriteLock(client, "/locks/catalog").use { rwLock ->
    if (rwLock.writeLock.tryLock(5.seconds)) {
      try {
        logger.info { "Got the write lock" }
      } finally {
        rwLock.writeLock.unlock()
      }
    }
  }
  // --8<-- [end:try-lock]
}

fun readWriteLockDowngrade(client: Client) {
  // --8<-- [start:downgrade]
  DistributedReadWriteLock(client, "/locks/catalog").use { rwLock ->
    rwLock.writeLock.withLock {
      logger.info { "Writing" }

      // Downgrading write -> read is safe: take the read lock before releasing
      // the write lock, and no other writer can slip in between.
      rwLock.readLock.withLock {
        logger.info { "Still holding the write lock while reading back" }
      }
    }
  }
  // --8<-- [end:downgrade]
}

fun readWriteLockUpgradeDeadlock(client: Client) {
  // --8<-- [start:upgrade]
  DistributedReadWriteLock(client, "/locks/catalog").use { rwLock ->
    rwLock.readLock.withLock {
      // DON'T: upgrading read -> write self-deadlocks, because the write lock
      // waits on a predecessor this very thread is holding. The recipe throws
      // rather than hanging forever.
      // rwLock.writeLock.lock()
      logger.info { "Release the read lock first, then take the write lock" }
    }
  }
  // --8<-- [end:upgrade]
}
