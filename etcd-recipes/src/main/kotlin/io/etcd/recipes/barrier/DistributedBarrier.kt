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
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.recipes.barrier.DistributedBarrier.Companion.defaultClientId
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.SelfHealingKeepAlive
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.selfHealingKeepAlive
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  // Plain var: all reads/writes are inside @Synchronized methods on this instance.
  private var keepAliveLease: SelfHealingKeepAlive? = null
  private val barrierRemoved = AtomicBoolean(false)

  init {
    require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
  }

  override val exceptionContext get() = "DistributedBarrier[$barrierPath]"

  fun isBarrierSet(): Boolean {
    checkCloseNotCalled()
    return client.isKeyPresent(barrierPath, resilience.rpc)
  }

  @Synchronized
  fun setBarrier(): Boolean {
    checkCloseNotCalled()
    return if (client.isKeyPresent(barrierPath, resilience.rpc)) {
      false
    } else {
      // Create unique token to avoid collision from clients with same id
      val uniqueToken = "$clientId:${randomId(TOKEN_LENGTH)}"

      // The barrier key is bound to a self-healing lease: if the lease expires
      // (partition longer than the TTL), waiters see a spurious lift — that window
      // is unavoidable, etcd deleted the key — but the healer re-arms the barrier
      // for future waiters and surfaces an Expired event so the owner knows. The
      // CAS is authoritative: on the initial attempt a loss aborts (another client
      // holds the barrier; the healer revokes its own lease before throwing). On a
      // heal-time loss another client re-set the barrier meanwhile — the barrier
      // stays armed, just not maintained by this instance (a Failed event says so).
      try {
        keepAliveLease = client.selfHealingKeepAlive(
          leaseTtlSecs.seconds,
          resilience.lease,
          leaseListener = { event -> onBarrierLeaseEvent(event) },
        ) { lease ->
          if (barrierRemoved.load()) {
            false // explicitly removed: do not re-arm
          } else {
            client.transaction(resilience.rpc) {
              If(barrierPath.doesNotExist)
              Then(barrierPath.setTo(uniqueToken, putOption { withLeaseId(lease.id) }))
            }.isSucceeded
          }
        }
        true
      } catch (e: EtcdRecipeRuntimeException) {
        // Initial CAS lost: another client set the barrier between the presence
        // check and the txn. The healer already revoked the lease it granted.
        logger.debug(e) { "setBarrier lost the CAS for $barrierPath" }
        false
      }
    }
  }

  // Record lease trouble on the exceptions list, drive connection state, and log
  // healing outcomes.
  private fun onBarrierLeaseEvent(event: LeaseEvent) {
    reportLeaseEvent(event)
    when (event) {
      is LeaseEvent.Suspended -> recordException(event.cause)

      is LeaseEvent.Expired -> recordException(
        event.cause ?: EtcdRecipeRuntimeException("Barrier lease for $barrierPath expired; healing"),
      )

      is LeaseEvent.Failed -> recordException(
        event.cause ?: EtcdRecipeRuntimeException("Barrier lease healing for $barrierPath abandoned"),
      )

      is LeaseEvent.Restored -> logger.info {
        "Barrier lease for $barrierPath healed: ${event.oldLeaseId} -> ${event.newLeaseId}"
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

      client.deleteKey(barrierPath, resilience.rpc)

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
      // Presence at wait start bounds the recovery recheck below: with
      // waitOnMissingBarriers=true a waiter on a never-set barrier must keep
      // waiting across recoveries, not release spuriously. The probe's revision
      // anchors the watch at observedRevision + 1 so a DELETE landing in the
      // watch-establishment window (between this probe and the watch going live)
      // is still delivered; the pre-live recheck below is then only a fast path.
      val startProbe = client.transaction(resilience.rpc) { If(barrierPath.doesExist) }
      val barrierPresentAtStart = startProbe.isSucceeded
      val observedRevision = startProbe.header.revision
      val waitLatch = CountDownLatch(1)
      val watchOption =
        watchOption {
          if (observedRevision > 0L) withRevision(observedRevision + 1)
          withNoPut(true)
        }
      val watchFailure = AtomicReference<Throwable?>()
      val recoveryListener = waiterRecoveryListener(barrierPresentAtStart, waitLatch, watchFailure)

      client.withWatcher(
        barrierPath,
        watchOption,
        resilience.watch,
        recoveryListener,
        resyncWith = null,
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

        val released = waitLatch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        watchFailure.get()?.let { cause ->
          throw EtcdRecipeRuntimeException("Barrier watch on $barrierPath failed while waiting", cause)
        }
        released
      }
    }
  }

  // The DELETE can be lost while the watch stream is fatally dead (compaction
  // resync, or a death before any event was ever observed). After each recovery,
  // re-probe the barrier and release the waiter if it is gone. An abandoned
  // recovery unparks the waiter with the failure recorded so the caller errors
  // out instead of parking until timeout.
  private fun waiterRecoveryListener(
    barrierPresentAtStart: Boolean,
    waitLatch: CountDownLatch,
    watchFailure: AtomicReference<Throwable?>,
  ): WatchRecoveryListener =
    WatchRecoveryListener { event ->
      reportRecoveryEvent(event)
      when (event) {
        is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
          if (!isBarrierSet() && (barrierPresentAtStart || !waitOnMissingBarriers))
            waitLatch.countDown()
        }

        is WatchRecoveryEvent.Failed -> {
          val cause = event.cause
            ?: EtcdRecipeRuntimeException("Watch on $barrierPath abandoned while waiting on barrier")
          watchFailure.set(cause)
          recordException(cause)
          waitLatch.countDown()
        }

        is WatchRecoveryEvent.Suspended -> {
          // jetcd (transient) or the recovery loop (fatal) is already on it
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
