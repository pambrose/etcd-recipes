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
import io.etcd.recipes.common.EstablishDeclinedException
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
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
  // Plain vars: all reads/writes are inside @Synchronized methods on this instance.
  private var keepAliveLease: SelfHealingKeepAlive? = null

  // The latest setBarrier()'s own "removed" flag, which its healer reads before every re-arm
  private var healerRetired: AtomicBoolean? = null

  // Whether removeBarrier() has run since the last successful setBarrier()
  private var barrierRemoved = false

  // Cancellation hooks of the in-flight waitOnBarrier calls, so close() can release
  // every parked waiter (reporting not-released) instead of leaving it to its timeout.
  private val activeWaiters = ConcurrentHashMap.newKeySet<() -> Unit>()

  init {
    require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
  }

  override val exceptionContext get() = "DistributedBarrier[$barrierPath]"

  // Raw read, deliberately without checkCloseNotCalled(). It runs on the waiter and
  // watch-dispatcher threads, where a concurrent close() must cancel the wait rather
  // than blow it up. close() does not close the client, so it stays valid afterward.
  // The public isBarrierSet() keeps the check.
  private val barrierKeyPresent: Boolean get() = client.isKeyPresent(barrierPath, resilience.rpc)

  fun isBarrierSet(): Boolean {
    checkCloseNotCalled()
    return barrierKeyPresent
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
      // A previous set's healer (its key since removed by someone else) is retired, not leaked
      retireHealer()
      val retired = AtomicBoolean(false)
      try {
        keepAliveLease = client.selfHealingKeepAlive(
          leaseTtlSecs.seconds,
          resilience.lease,
          leaseListener = { event -> onBarrierLeaseEvent(event) },
          rpc = resilience.rpc,
        ) { lease ->
          if (retired.load()) {
            false // explicitly removed: do not re-arm
          } else {
            client.transaction(resilience.rpc) {
              If(barrierPath.doesNotExist)
              Then(barrierPath.setTo(uniqueToken, putOption { withLeaseId(lease.id) }))
            }.isSucceeded
          }
        }
        healerRetired = retired
        barrierRemoved = false
        true
      } catch (e: EstablishDeclinedException) {
        // Initial CAS lost: another client set the barrier between the presence
        // check and the txn. The healer already revoked the lease it granted. Any
        // other failure (an unreachable etcd, a refused grant) is not a lost CAS and
        // propagates with its cause.
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
    return if (barrierRemoved) {
      false
    } else {
      retireHealer()
      client.deleteKey(barrierPath, resilience.rpc)
      barrierRemoved = true
      true
    }
  }

  // Stops this instance's healer. Its flag is set before the close, so a heal racing this
  // can't re-arm the barrier.
  private fun retireHealer() {
    healerRetired?.store(true)
    healerRetired = null
    keepAliveLease?.close()
    keepAliveLease = null
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
    return if (!waitOnMissingBarriers && !barrierKeyPresent) {
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
      val watchFailure = AtomicReference<Throwable?>(null)
      val recoveryListener = waiterRecoveryListener(barrierPresentAtStart, waitLatch, watchFailure)

      // Register a cancellation hook so close() can release this waiter.
      val cancelled = AtomicBoolean(false)
      val cancelWait: () -> Unit = {
        cancelled.store(true)
        waitLatch.countDown()
      }
      activeWaiters += cancelWait
      // close() sets closeCalled before doClose() runs the registered hooks, so a close()
      // that slipped in before this registration is seen here.
      if (closeCalled.load()) cancelWait()

      try {
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
          if (!waitOnMissingBarriers && !barrierKeyPresent)
            waitLatch.countDown()

          val released = waitLatch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
          watchFailure.load()?.let { cause ->
            throw EtcdRecipeRuntimeException("Barrier watch on $barrierPath failed while waiting", cause)
          }
          // A wait cancelled by close() reports not-released.
          released && !cancelled.load()
        }
      } finally {
        activeWaiters -= cancelWait
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
      withRecipeLoggingContext {
        reportRecoveryEvent(event)
        when (event) {
          is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
            if (!barrierKeyPresent && (barrierPresentAtStart || !waitOnMissingBarriers))
              waitLatch.countDown()
          }

          is WatchRecoveryEvent.Failed -> {
            val cause = event.cause
              ?: EtcdRecipeRuntimeException("Watch on $barrierPath abandoned while waiting on barrier")
            watchFailure.store(cause)
            recordException(cause)
            waitLatch.countDown()
          }

          is WatchRecoveryEvent.Suspended -> {
            // jetcd (transient) or the recovery loop (fatal) is already on it
          }
        }
      }
    }

  @Synchronized
  override fun doClose() {
    retireHealer()
    activeWaiters.forEach { cancelWait -> cancelWait() }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    internal fun defaultClientId() = EtcdConnector.defaultClientId(DistributedBarrier::class.simpleName!!)
  }
}
