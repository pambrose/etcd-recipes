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

import com.pambrose.common.concurrent.BooleanMonitor
import com.pambrose.common.time.timeUnitToDuration
import com.pambrose.common.util.ensureSuffix
import com.pambrose.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.barrier.DistributedBarrierWithCount.Companion.defaultClientId
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.SelfHealingKeepAlive
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.selfHealingKeepAlive
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/*
    First node creates subnode /ready
    Each node creates its own subnode with keepalive on it
    Each node creates a watch for DELETE on /ready and PUT on any waiter
    Query the number of children after each PUT on waiter and DELETE /ready if memberCount seen
    Leave if DELETE of /ready is seen
*/

@JvmOverloads
fun <T> withDistributedBarrierWithCount(
  client: Client,
  barrierPath: String,
  memberCount: Int,
  leaseTtlSecs: Long = EtcdConnector.DEFAULT_TTL_SECS,
  clientId: String = defaultClientId(),
  receiver: DistributedBarrierWithCount.() -> T,
): T = DistributedBarrierWithCount(client, barrierPath, memberCount, leaseTtlSecs, clientId).use { it.receiver() }

class DistributedBarrierWithCount
@JvmOverloads
constructor(
  client: Client,
  val barrierPath: String,
  val memberCount: Int,
  val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  val clientId: String = defaultClientId(),
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  private val readyPath = barrierPath.appendToPath("ready")
  private val waitingPath = barrierPath.appendToPath("waiting")

  // Cancellation hook stashed by an in-flight waitOnBarrier so that close()
  // can unblock a waiting thread instead of leaving it parked indefinitely.
  // Holds the keep-alive client and the waiting key path so close() can
  // release them on behalf of the waiter.
  private val activeWaiter = AtomicReference<ActiveWait?>(null)

  private class ActiveWait(
    val keepAliveClosed: BooleanMonitor,
    val cancelled: BooleanMonitor,
    val onCancel: () -> Unit,
  )

  init {
    require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
    require(memberCount > 0) { "Member count must be > 0" }
  }

  override val exceptionContext get() = "DistributedBarrierWithCount[$barrierPath]"

  private val isReadySet: Boolean
    get() {
      checkCloseNotCalled()
      return client.isKeyPresent(readyPath, resilience.rpc)
    }

  val waiterCount: Long
    get() {
      checkCloseNotCalled()
      return client.getChildCount(waitingPath, resilience.rpc)
    }

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun waitOnBarrier(): Boolean = waitOnBarrier(Long.MAX_VALUE.days)

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun waitOnBarrier(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = waitOnBarrier(timeUnitToDuration(timeout, timeUnit))

  @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun waitOnBarrier(timeout: Duration): Boolean {
    val keepAliveLease = AtomicReference<SelfHealingKeepAlive?>(null)
    val keepAliveClosed = BooleanMonitor(false)
    val cancelled = BooleanMonitor(false)
    val uniqueToken = "$clientId:${randomId(TOKEN_LENGTH)}"
    val myWaitingPath = waitingPath.appendToPath(uniqueToken)
    val waitingPrefix = waitingPath.ensureSuffix("/")

    checkCloseNotCalled()

    fun closeKeepAlive() {
      // Atomically claim the keep-alive client so it is closed exactly once across the
      // waiter / watch-dispatcher / close() threads: whoever exchanges the non-null ref
      // is the unique closer. Driving idempotency off the exchange (rather than a
      // check-then-act on keepAliveClosed) also closes the leak where a close() that
      // arrived before the client was assigned would flip keepAliveClosed and strand
      // the just-created client. keepAliveClosed stays purely the wait/signal flag.
      keepAliveLease.exchange(null)?.let { kac ->
        kac.close()
        runCatching { client.deleteKey(myWaitingPath, resilience.rpc) }
      }
      keepAliveClosed.set(true)
    }

    fun checkWaiterCount() {
      // First see if /ready is missing
      if (!isReadySet) {
        closeKeepAlive()
      } else {
        if (waiterCount >= memberCount) {
          closeKeepAlive()

          // Delete /ready key
          client.transaction(resilience.rpc) {
            If(readyPath.doesExist)
            Then(deleteOp(readyPath))
          }
        }
      }
    }

    // Register a cancellation hook so close() can unblock this waiter.
    val active = ActiveWait(keepAliveClosed, cancelled) {
      cancelled.set(true)
      closeKeepAlive()
    }
    activeWaiter.store(active)

    try {
      // Do a CAS on the /ready name. If it is not found, then set it
      client.transaction(resilience.rpc) {
        If(readyPath.doesNotExist)
        Then(readyPath setTo uniqueToken)
      }

      // The waiting key is bound to a self-healing lease: if it expires while the
      // waiter is parked (partition longer than the TTL), the healer re-registers
      // it so the barrier can still trip. Healing stops once the barrier lifted or
      // the wait was cancelled (keepAliveClosed). A heal-time CAS loss means the
      // key unexpectedly exists — ownership is not reclaimed.
      val healer =
        try {
          client.selfHealingKeepAlive(
            leaseTtlSecs.seconds,
            resilience.lease,
            leaseListener = { event -> onWaiterLeaseEvent(event) },
          ) { lease ->
            if (keepAliveClosed.get()) {
              false
            } else {
              client.transaction(resilience.rpc) {
                If(myWaitingPath.doesNotExist)
                Then(myWaitingPath.setTo(uniqueToken, putOption { withLeaseId(lease.id) }))
              }.isSucceeded
            }
          }
        } catch (e: EtcdRecipeRuntimeException) {
          // Initial CAS lost (the healer already revoked its lease).
          logger.debug(e) { "Waiting-path CAS lost for $myWaitingPath" }
          throw EtcdRecipeException("Failed to set waitingPath")
        }

      // No getValue re-read: the establish CAS already proves this client set the
      // waiting-path key (the re-read only guarded a commit-to-read race window).

      // Keep key alive (self-healing across lease expiry)
      keepAliveLease.store(healer)

      // Reconcile with a close() that may have fired before the store above: its
      // closeKeepAlive() would have found a null ref and closed nothing, stranding
      // the just-created client. onCancel sets `cancelled` before calling
      // closeKeepAlive(), so observing it here means we own closing our client.
      if (cancelled.get()) closeKeepAlive()

      checkWaiterCount()

      // Do not bother starting watcher if latch is already done
      return if (keepAliveClosed.get()) {
            // Cancellation by close() also flips keepAliveClosed; report
            // satisfied-only-when-not-cancelled.
            !cancelled.get()
          } else {
            // Watch for DELETE of /ready and PUTS on /waiters/*
            val trailingKey = barrierPath.ensureSuffix("/")
            // Anchor the prefix watch at observedRevision + 1 so a /ready DELETE or
            // waiter PUT landing in the watch-establishment window is still delivered;
            // checkWaiterCount below is then only a fast-path recheck.
            val observedRevision =
              client.getResponse(
                trailingKey,
                getOption { isPrefix(true).withCountOnly(true) },
                resilience.rpc,
              ).header.revision
            val watchOption =
              watchOption {
                if (observedRevision > 0L) withRevision(observedRevision + 1)
                isPrefix(true)
              }
            val watchFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()

            // A ready-key DELETE or waiter PUT can be lost while the watch stream is
            // fatally dead. After each recovery, checkWaiterCount() re-probes both
            // conditions (ready gone / member count reached) exactly like the
            // pre-park recheck below. An abandoned recovery unparks the waiter with
            // the failure recorded so the caller errors out instead of parking.
            val recoveryListener =
              WatchRecoveryListener { event ->
                withRecipeLoggingContext {
                  reportRecoveryEvent(event)
                  when (event) {
                    is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
                      checkWaiterCount()
                    }

                    is WatchRecoveryEvent.Failed -> {
                      val cause = event.cause
                        ?: EtcdRecipeRuntimeException("Watch on $barrierPath abandoned while waiting on barrier")
                      watchFailure.set(cause)
                      recordException(cause)
                      closeKeepAlive()
                    }

                    is WatchRecoveryEvent.Suspended -> {
                      // jetcd (transient) or the recovery loop (fatal) is already on it
                    }
                  }
                }
              }

            client.withWatcher(
              trailingKey,
              watchOption,
              resilience.watch,
              recoveryListener,
              resyncWith = null,
              { watchResponse ->
                watchResponse.events
                  .forEach { watchEvent ->
                    val key = watchEvent.keyValue.key.asString
                    when {
                      key.startsWith(waitingPrefix) && watchEvent.eventType == PUT -> checkWaiterCount()
                      key == readyPath && watchEvent.eventType == DELETE -> closeKeepAlive()
                    }
                  }
              },
            ) {
              // Check one more time in case watch missed the delete just after last check
              checkWaiterCount()

              val signalled = keepAliveClosed.waitUntilTrueWithInterruption(timeout)
              // Cleanup if a time-out occurred
              if (!signalled) {
                closeKeepAlive()
                // Redundant, but waiting for the keep-alive to stop is slower
                client.deleteKey(myWaitingPath, resilience.rpc)
              }

              watchFailure.get()?.let { cause ->
                throw EtcdRecipeRuntimeException("Barrier watch on $barrierPath failed while waiting", cause)
              }

              // Distinguish natural completion from cancellation.
              signalled && !cancelled.get()
            }
          }
    } finally {
      activeWaiter.compareAndSet(active, null)
      // Stop counting toward the barrier on ANY exit path — normal trip, timeout,
      // or an exception (e.g. a coroutine bridge cancelled the wait, interrupting a
      // blocking RPC). closeKeepAlive() is idempotent and halts the healer, so the
      // waiting key is removed (or expires at its TTL) rather than lingering as a
      // phantom participant. The deleteKey inside it is best-effort under a set
      // interrupt flag.
      closeKeepAlive()
    }
  }

  override fun doClose() {
    activeWaiter.exchange(null)?.onCancel?.invoke()
  }

  // Record lease trouble on the exceptions list, drive connection state, and log
  // healing outcomes.
  private fun onWaiterLeaseEvent(event: LeaseEvent) {
    withRecipeLoggingContext {
      reportLeaseEvent(event)
      when (event) {
        is LeaseEvent.Suspended -> recordException(event.cause)

        is LeaseEvent.Expired -> recordException(
          event.cause ?: EtcdRecipeRuntimeException("Waiter lease for $barrierPath expired; healing"),
        )

        is LeaseEvent.Failed -> recordException(
          event.cause ?: EtcdRecipeRuntimeException("Waiter lease healing for $barrierPath abandoned"),
        )

        is LeaseEvent.Restored -> logger.info {
          "Waiter lease for $barrierPath healed: ${event.oldLeaseId} -> ${event.newLeaseId}"
        }
      }
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    internal fun defaultClientId() = defaultClientId(DistributedBarrierWithCount::class.simpleName!!)
  }
}
