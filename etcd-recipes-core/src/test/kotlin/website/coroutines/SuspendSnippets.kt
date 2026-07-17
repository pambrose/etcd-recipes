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

package website.coroutines

import io.etcd.jetcd.Client
import io.etcd.recipes.barrier.DistributedDoubleBarrier
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.setTo
import io.etcd.recipes.coroutines.awaitAcquire
import io.etcd.recipes.coroutines.awaitAvailablePermits
import io.etcd.recipes.coroutines.awaitEnqueue
import io.etcd.recipes.coroutines.awaitEnter
import io.etcd.recipes.coroutines.awaitGet
import io.etcd.recipes.coroutines.awaitGetValue
import io.etcd.recipes.coroutines.awaitIncrement
import io.etcd.recipes.coroutines.awaitLeaseGrant
import io.etcd.recipes.coroutines.awaitLeaseRevoke
import io.etcd.recipes.coroutines.awaitLeave
import io.etcd.recipes.coroutines.awaitLock
import io.etcd.recipes.coroutines.awaitPutValue
import io.etcd.recipes.coroutines.awaitRelease
import io.etcd.recipes.coroutines.awaitStart
import io.etcd.recipes.coroutines.awaitStartComplete
import io.etcd.recipes.coroutines.awaitTransaction
import io.etcd.recipes.coroutines.awaitUnlock
import io.etcd.recipes.coroutines.receive
import io.etcd.recipes.coroutines.withLock
import io.etcd.recipes.coroutines.withPermit
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.queue.DistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

suspend fun suspendingKv(client: Client) {
  // --8<-- [start:kv]
  client.awaitPutValue("/config/mode", "active")
  val mode = client.awaitGetValue("/config/mode", "unknown")
  logger.info { "Mode is $mode" }

  // Transactions get the operation timeout but are never retried: a failed commit
  // is ambiguous, so the CAS retry decision stays yours.
  val claimed =
    client.awaitTransaction {
      If("/leases/job-1".doesNotExist)
      Then("/leases/job-1" setTo "worker-a")
    }
  logger.info { "Claimed the job: ${claimed.isSucceeded}" }
  // --8<-- [end:kv]
}

suspend fun suspendingLifecycle(client: Client) {
  // --8<-- [start:lifecycle]
  PathChildrenCache(client, "/services").use { cache ->
    // Both the start RPCs and the wait for the initial snapshot happen off-thread.
    cache.awaitStart(buildInitial = true)
    cache.awaitStartComplete(5.seconds)
    logger.info { "Cache holds ${cache.currentData.size} children" }
  }
  // --8<-- [end:lifecycle]
}

suspend fun suspendingCounter(client: Client) {
  // --8<-- [start:counter]
  DistributedAtomicLong(client, "/counters/orders").use { counter ->
    counter.awaitStart()
    val next = counter.awaitIncrement()
    val total = counter.awaitGet()
    logger.info { "Order $next of $total" }
  }
  // --8<-- [end:counter]
}

suspend fun suspendingQueue(client: Client) {
  // --8<-- [start:queue]
  DistributedQueue(client, "/queues/jobs").use { queue ->
    queue.awaitEnqueue("job-1")

    // receive() parks the coroutine, not a thread: thousands of consumers cost
    // thousands of continuations rather than thousands of stacks.
    val item = queue.receive()
    logger.info { "Got ${item.asString}" }
  }
  // --8<-- [end:queue]
}

suspend fun suspendingBarrier(client: Client) {
  // --8<-- [start:barrier]
  DistributedDoubleBarrier(client, "/barriers/phase-1", memberCount = 5).use { barrier ->
    barrier.awaitEnter(30.seconds)
    logger.info { "All five members arrived; running phase 1" }
    barrier.awaitLeave(30.seconds)
  }
  // --8<-- [end:barrier]
}

suspend fun cancellationWithTimeout(client: Client) {
  // --8<-- [start:cancellation-timeout]
  DistributedQueue(client, "/queues/jobs").use { queue ->
    // Cancelling a parked receive() consumes nothing: the wait is interrupted, the
    // recipe's cleanup runs, and the queue is left exactly as it was found.
    val item = withTimeoutOrNull(3.seconds) { queue.receive() }
    if (item == null) logger.info { "Nothing to do; idling" }
  }
  // --8<-- [end:cancellation-timeout]
}

fun cancellationSurfaces(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:cancellation-surfaces]
  val consumer =
    scope.launch(Dispatchers.Default) {
      DistributedQueue(client, "/queues/jobs").use { queue ->
        try {
          val item = queue.receive()
          logger.info { "Got ${item.asString}" }
        } catch (e: CancellationException) {
          // The worker thread was interrupted, the recipe's finally-block cleanup
          // (lease revoke / entry delete) already ran, and the interrupt reaches
          // you here as CancellationException — never as a raw EtcdRecipe*Exception.
          logger.info { "Consumer shut down cleanly: ${e.message}" }
          throw e
        }
      }
    }
  consumer.cancel()
  // --8<-- [end:cancellation-surfaces]
}

suspend fun suspendingWithLock(client: Client) {
  // --8<-- [start:with-lock]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    // Scoped-only: acquisition and release are confined to one dedicated thread,
    // while the body runs in the caller's coroutine and may suspend freely.
    mutex.withLock {
      logger.info { "Critical section, without pinning a thread to the wait" }
    }
  }
  // --8<-- [end:with-lock]
}

suspend fun suspendingWithLockTimeout(client: Client) {
  // --8<-- [start:with-lock-timeout]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    // A null return always means "not acquired" — nothing was left queued.
    val result =
      mutex.withLock(5.seconds) {
        logger.info { "Acquired within the timeout" }
        "done"
      }
    if (result == null) logger.info { "Someone else holds the lock; moving on" }
  }
  // --8<-- [end:with-lock-timeout]
}

suspend fun suspendingWithPermit(client: Client) {
  // --8<-- [start:with-permit]
  DistributedSemaphore(client, "/semaphores/uploads", permits = 3).use { semaphore ->
    semaphore.withPermit {
      logger.info { "At most three uploaders cluster-wide run this" }
    }
  }
  // --8<-- [end:with-permit]
}

suspend fun suspendingSemaphoreSplit(client: Client) {
  // --8<-- [start:semaphore-split]
  DistributedSemaphore(client, "/semaphores/uploads", permits = 3).use { semaphore ->
    // Permits are held by the instance, not by a thread, so the split surface is
    // safe across dispatcher hops — acquire here, release from anywhere.
    semaphore.awaitAcquire()
    try {
      val left = semaphore.awaitAvailablePermits()
      logger.info { "$left permits left" }
    } finally {
      semaphore.awaitRelease()
    }
  }
  // --8<-- [end:semaphore-split]
}

suspend fun rawSuspendingLock(client: Client) {
  // --8<-- [start:raw-lock]
  // The raw pass-throughs; prefer DistributedMutex. awaitLock defaults to
  // RpcResilience.DISABLED because the lock RPC legitimately waits server-side.
  val lease = client.awaitLeaseGrant(10.seconds)
  try {
    val lock = client.awaitLock("/locks/raw", lease.id, rpc = RpcResilience.DISABLED)
    try {
      logger.info { "Holding ${lock.key.asString}" }
    } finally {
      client.awaitUnlock("/locks/raw")
    }
  } finally {
    client.awaitLeaseRevoke(lease)
  }
  // --8<-- [end:raw-lock]
}
