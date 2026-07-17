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

package website.barrier

import io.etcd.jetcd.Client
import io.etcd.recipes.barrier.DistributedBarrier
import io.etcd.recipes.barrier.DistributedBarrierWithCount
import io.etcd.recipes.barrier.DistributedDoubleBarrier
import io.etcd.recipes.barrier.withDistributedBarrier
import io.etcd.recipes.barrier.withDistributedBarrierWithCount
import io.etcd.recipes.barrier.withDistributedDoubleBarrier
import io.etcd.recipes.coroutines.await
import io.etcd.recipes.coroutines.awaitEnter
import io.etcd.recipes.coroutines.awaitLeave
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun armBarrier(client: Client) {
  // --8<-- [start:set]
  DistributedBarrier(client, "/barriers/import").use { barrier ->
    // false means another client already holds the barrier — this instance did
    // not arm it, and must not assume it may lift it.
    if (barrier.setBarrier()) {
      logger.info { "Barrier armed; every waiter blocks" }

      // ... do the work that the waiters must not race ...

      // Releases every waiter at once.
      barrier.removeBarrier()
    } else {
      logger.info { "Someone else armed it" }
    }
  }
  // --8<-- [end:set]
}

fun waitOnBarrier(client: Client) {
  // --8<-- [start:wait]
  DistributedBarrier(client, "/barriers/import").use { barrier ->
    // Advisory: true-at-some-recent-revision, and the barrier may lift a
    // microsecond later. Never branch on it to decide whether to wait.
    if (barrier.isBarrierSet()) logger.info { "Barrier is up; blocking" }

    // Blocks until the barrier key is deleted.
    barrier.waitOnBarrier()
    logger.info { "Released" }
  }
  // --8<-- [end:wait]
}

fun waitOnBarrierWithTimeout(client: Client) {
  // --8<-- [start:wait-timeout]
  DistributedBarrier(client, "/barriers/import").use { barrier ->
    // false means the timeout elapsed with the barrier still up. Waiting again
    // is fine — a waiter holds no state between calls.
    if (barrier.waitOnBarrier(30.seconds)) {
      logger.info { "Released within the timeout" }
    } else {
      logger.info { "Still blocked after 30s" }
    }
  }
  // --8<-- [end:wait-timeout]
}

fun waitOnMissingBarrier(client: Client) {
  // --8<-- [start:missing]
  // waitOnMissingBarriers=false: an unset barrier is treated as already lifted,
  // so waitOnBarrier() returns true immediately instead of blocking until some
  // client arms and then removes it.
  DistributedBarrier(
    client = client,
    barrierPath = "/barriers/import",
    leaseTtlSecs = 5L,
    waitOnMissingBarriers = false,
  ).use { barrier ->
    barrier.waitOnBarrier()
  }
  // --8<-- [end:missing]
}

fun barrierLeaseLoss(client: Client) {
  // --8<-- [start:lease-loss]
  DistributedBarrier(client, "/barriers/import", leaseTtlSecs = 5L).use { barrier ->
    barrier.addConnectionStateListener { new, prev -> logger.warn { "Connection state: $prev -> $new" } }

    // A lease expiry means etcd already deleted the barrier key, so waiters saw a
    // spurious lift. The healer re-grants a lease and re-arms the barrier for
    // future waiters; the expiry itself lands on the exceptions list.
    barrier.addBackgroundExceptionListener { context, e ->
      logger.warn(e) { "Barrier lease trouble in $context" }
    }

    barrier.setBarrier()
    barrier.removeBarrier()
    barrier.exceptions.forEach { logger.warn(it) { "Background failure" } }
  }
  // --8<-- [end:lease-loss]
}

fun scopedBarrier(client: Client) {
  // --8<-- [start:scoped]
  withDistributedBarrier(client, "/barriers/import") {
    setBarrier()
    removeBarrier()
  }
  // --8<-- [end:scoped]
}

fun barrierWithCount(client: Client) {
  // --8<-- [start:with-count]
  // Every one of the five members runs this. Nobody proceeds until all five are
  // parked here; then all five leave together.
  DistributedBarrierWithCount(client, "/barriers/phase1", 5).use { barrier ->
    logger.info { "${barrier.waiterCount} of ${barrier.memberCount} waiting" }
    barrier.waitOnBarrier()
    logger.info { "All ${barrier.memberCount} arrived" }
  }
  // --8<-- [end:with-count]
}

fun barrierWithCountTimeout(client: Client) {
  // --8<-- [start:with-count-timeout]
  DistributedBarrierWithCount(client, "/barriers/phase1", 5).use { barrier ->
    // A timeout drops this member out of the count until it waits again, so a
    // straggler cannot be counted twice.
    if (!barrier.waitOnBarrier(30.seconds)) {
      logger.warn { "Only ${barrier.waiterCount} of ${barrier.memberCount} showed up" }
    }
  }
  // --8<-- [end:with-count-timeout]
}

fun scopedBarrierWithCount(client: Client) {
  // --8<-- [start:with-count-scoped]
  withDistributedBarrierWithCount(client, "/barriers/phase1", 5) {
    waitOnBarrier()
  }
  // --8<-- [end:with-count-scoped]
}

fun doubleBarrier(client: Client) {
  // --8<-- [start:double]
  // Two DistributedBarrierWithCount instances under the covers, at
  // /barriers/phase1/enter and /barriers/phase1/leave.
  DistributedDoubleBarrier(client, "/barriers/phase1", 5).use { barrier ->
    logger.info { "${barrier.enterWaiterCount} peers already at the gate" }

    // Nobody starts the phase until all five have arrived.
    barrier.enter()

    // ... run the phase ...

    // Nobody tears down until all five have finished it.
    barrier.leave()
    logger.info { "${barrier.leaveWaiterCount} peers still leaving" }
  }
  // --8<-- [end:double]
}

fun doubleBarrierTimeout(client: Client) {
  // --8<-- [start:double-timeout]
  withDistributedDoubleBarrier(client, "/barriers/phase1", 5) {
    if (enter(30.seconds)) {
      // Bound the leave too: a peer that dies mid-phase must not park the rest
      // of the cluster forever.
      if (!leave(30.seconds)) logger.warn { "Only $leaveWaiterCount left cleanly" }
    } else {
      logger.warn { "Only $enterWaiterCount entered; abandoning the phase" }
    }
  }
  // --8<-- [end:double-timeout]
}

suspend fun suspendingBarriers(client: Client) {
  // --8<-- [start:coroutines]
  DistributedBarrier(client, "/barriers/import").use { barrier ->
    // Releases the thread while parked, and cancellation unblocks the wait.
    barrier.await(30.seconds)
  }

  DistributedDoubleBarrier(client, "/barriers/phase1", 5).use { barrier ->
    barrier.awaitEnter()
    barrier.awaitLeave()
  }
  // --8<-- [end:coroutines]
}
