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
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.lock.SemaphorePermitMismatchException
import io.etcd.recipes.lock.withPermit
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun basicSemaphore(client: Client) {
  // --8<-- [start:basic]
  // At most three clients cluster-wide run the guarded section at once.
  DistributedSemaphore(client, "/semaphores/api-quota", permits = 3).use { semaphore ->
    semaphore.withPermit {
      logger.info { "Calling the rate-limited API" }
    }
  }
  // --8<-- [end:basic]
}

fun semaphoreTryAcquire(client: Client) {
  // --8<-- [start:try-acquire]
  DistributedSemaphore(client, "/semaphores/api-quota", permits = 3).use { semaphore ->
    if (semaphore.tryAcquire(2.seconds)) {
      try {
        logger.info { "Got a permit; ${semaphore.availablePermits()} left (advisory)" }
      } finally {
        semaphore.release()
      }
    } else {
      logger.info { "All permits taken; shedding load" }
    }
  }
  // --8<-- [end:try-acquire]
}

fun semaphoreMultiplePermits(client: Client) {
  // --8<-- [start:multiple]
  DistributedSemaphore(client, "/semaphores/api-quota", permits = 3).use { semaphore ->
    // Holds are instance-level, not thread-owned: unlike EtcdLock, any thread may
    // release a permit taken by another. Releases are LIFO.
    semaphore.acquire()
    semaphore.acquire()
    logger.info { "Holding two permits" }
    semaphore.release()
    semaphore.release()
  }
  // --8<-- [end:multiple]
}

fun semaphorePermitLost(client: Client) {
  // --8<-- [start:permit-lost]
  DistributedSemaphore(client, "/semaphores/api-quota", permits = 3).use { semaphore ->
    semaphore.addPermitLostListener { cause ->
      logger.warn { "Permit lost, stop doing the guarded work: $cause" }
    }
    semaphore.withPermit { logger.info { "Working" } }
  }
  // --8<-- [end:permit-lost]
}

fun semaphoreMismatch(client: Client) {
  // --8<-- [start:mismatch]
  // The permit count is CAS-created at <path>/permits by whoever gets there first.
  // A later instance naming the same path with a different count is a programming
  // error, not a silent reconfiguration, so it throws.
  try {
    DistributedSemaphore(client, "/semaphores/api-quota", permits = 5).use { semaphore ->
      semaphore.withPermit { logger.info { "Never reached if 3 was established first" } }
    }
  } catch (e: SemaphorePermitMismatchException) {
    logger.error { "Requested ${e.requestedPermits}, but the path is fixed at ${e.canonicalPermits}" }
  }
  // --8<-- [end:mismatch]
}
