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

package io.etcd.recipes.barrier

import com.pambrose.common.time.timeUnitToDuration
import com.pambrose.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.jetcd.support.CloseableClient
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.recipes.barrier.DistributedBarrier.Companion.defaultClientId
import io.etcd.recipes.common.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@JvmOverloads
fun <T> withDistributedBarrier(
  client: Client,
  barrierPath: String,
  leaseTtlSecs: Long = EtcdConnector.DEFAULT_TTL_SECS,
  waitOnMissingBarriers: Boolean = true,
  clientId: String = defaultClientId(),
  receiver: DistributedBarrier.() -> T,
): T = DistributedBarrier(client, barrierPath, leaseTtlSecs, waitOnMissingBarriers, clientId).use { it.receiver() }

class DistributedBarrier
@JvmOverloads
constructor(
  client: Client,
  val barrierPath: String,
  val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  private val waitOnMissingBarriers: Boolean = true,
  val clientId: String = defaultClientId(),
) : EtcdConnector(client) {
  // Plain var: all reads/writes are inside @Synchronized methods on this instance.
  private var keepAliveLease: CloseableClient? = null
  private val barrierRemoved = AtomicBoolean(false)

  init {
    require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
  }

  fun isBarrierSet(): Boolean {
    checkCloseNotCalled()
    return client.isKeyPresent(barrierPath)
  }

  @Synchronized
  fun setBarrier(): Boolean {
    checkCloseNotCalled()
    return if (client.isKeyPresent(barrierPath)) {
      false
    } else {
      // Create unique token to avoid collision from clients with same id
      val uniqueToken = "$clientId:${randomId(TOKEN_LENGTH)}"

      // Prime lease with 2 seconds to give keepAlive a chance to get started
      val lease = client.leaseGrant(leaseTtlSecs.seconds)

      // Do a CAS on the key name. If it is not found, then set it
      val txn =
        client.transaction {
          If(barrierPath.doesNotExist)
          Then(barrierPath.setTo(uniqueToken, putOption { withLeaseId(lease.id) }))
        }

      // Check to see if unique value was successfully set in the CAS step
      if (txn.isSucceeded && client.getValue(barrierPath)?.asString == uniqueToken) {
        keepAliveLease = client.keepAlive(lease)
        true
      } else {
        // Lease leaked the original implementation: when the CAS lost or the
        // value verification failed we returned without ever revoking the
        // lease, so it sat in etcd until TTL — wasteful in tight retry loops.
        client.leaseRevoke(lease)
        false
      }
    }
  }

  @Synchronized
  fun removeBarrier(): Boolean {
    checkCloseNotCalled()
    return if (barrierRemoved.load()) {
      false
    } else {
      keepAliveLease?.close()
      keepAliveLease = null

      client.deleteKey(barrierPath)

      barrierRemoved.store(true)

      true
    }
  }

  @Throws(InterruptedException::class)
  fun waitOnBarrier(): Boolean = waitOnBarrier(Long.MAX_VALUE.days)

  @Throws(InterruptedException::class)
  fun waitOnBarrier(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = waitOnBarrier(timeUnitToDuration(timeout, timeUnit))

  @Throws(InterruptedException::class)
  fun waitOnBarrier(timeout: Duration): Boolean {
    checkCloseNotCalled()

    // Check if barrier is present before using watcher
    return if (!waitOnMissingBarriers && !isBarrierSet()) {
      true
    } else {
      val waitLatch = CountDownLatch(1)
      val watchOption = watchOption { withNoPut(true) }

      client.withWatcher(
        barrierPath,
        watchOption,
        { watchResponse ->
          for (event in watchResponse.events) {
            if (event.eventType == DELETE) {
              waitLatch.countDown()
            }
          }
        },
      ) {
        // Check one more time in case watch missed the delete just after last check
        if (!waitOnMissingBarriers && !isBarrierSet())
          waitLatch.countDown()

        waitLatch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      }
    }
  }

  @Synchronized
  override fun doClose() {
    keepAliveLease?.close()
    keepAliveLease = null
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    internal fun defaultClientId() = EtcdConnector.defaultClientId(DistributedBarrier::class.simpleName!!)
  }
}
