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
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.barrier.DistributedBarrierWithCount.Companion.defaultClientId
import io.etcd.recipes.common.EstablishDeclinedException
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.SelfHealingKeepAlive
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.retryRpc
import io.etcd.recipes.common.selfHealingKeepAlive
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/*
    A round is identified by /ready's createRevision: the first arrival creates /ready, and
      later ones join the round it names
    Each node creates its own subnode waiting/<round>/<token> with keepalive on it
    Each node creates a watch for DELETE on /ready and PUT on any waiter of its round
    Count the round's waiters after each PUT and, if memberCount is seen, DELETE that round's
      /ready (guarded on its createRevision) before leaving
    Leave if DELETE of /ready is seen, or /ready names a later round
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

  // Cancellation hooks of the in-flight waitOnBarrier calls, so close() can unblock
  // every waiting thread (not just the latest) instead of leaving them parked. Each
  // hook marks its wait cancelled and releases that waiter's keep-alive and key.
  private val activeWaiters = ConcurrentHashMap.newKeySet<() -> Unit>()

  init {
    require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
    require(memberCount > 0) { "Member count must be > 0" }
  }

  override val exceptionContext get() = "DistributedBarrierWithCount[$barrierPath]"

  // Raw reads, deliberately without checkCloseNotCalled(). These run on the waiter and
  // watch-dispatcher threads, where a concurrent close() must cancel the wait rather than
  // blow it up: the waiter is between its establish CAS and the park for several RPCs, and
  // a throw there escapes waitOnBarrier instead of unparking it with a cancellation (on the
  // dispatcher thread it would kill the callback outright). close() does not close the
  // client, so these stay valid afterward. The public getter below keeps the check.
  // currentRound() is the round in progress (/ready's createRevision), or null when there is none.
  private fun currentRound(): Long? =
    client.getResponse(readyPath, rpc = resilience.rpc).kvs.firstOrNull()?.createRevision

  private fun roundPath(round: Long) = waitingPath.appendToPath(round.toString())

  /** The waiters registered in the round in progress; 0 when there is none. */
  val waiterCount: Long
    get() {
      checkCloseNotCalled()
      val round = currentRound() ?: return 0L
      return client.getChildCount(roundPath(round), resilience.rpc)
    }

  // Joins the round in progress, or starts one, and returns it.
  private fun joinRound(token: String): Long {
    val response =
      client.transaction(resilience.rpc) {
        If(readyPath.doesNotExist)
        Then(readyPath setTo token)
        Else(Op.get(readyPath.asByteSequence, GetOption.DEFAULT))
      }
    // A started round is the revision that created /ready; a joined one, /ready's createRevision
    return if (response.isSucceeded)
      response.header.revision
    else
      response.getResponses.first().kvs.first().createRevision
  }

  // Ends [round] by deleting its /ready. Guarded on /ready's createRevision, the delete is
  // idempotent — a retry after an ambiguous commit finds it gone and does nothing — so unlike
  // other transactions it is retried on a transient failure. False, and recorded, when it
  // can't be committed: the caller stays parked rather than leave a round that still stands.
  private fun releaseRound(round: Long): Boolean =
    try {
      retryRpc(resilience.rpc, "releaseBarrier($readyPath)") {
        client.kvClient
          .txn()
          .If(equalTo(readyPath, CmpTarget.createRevision(round)))
          .Then(deleteOp(readyPath))
          .commit()
      }
      true
    } catch (e: EtcdRecipeRuntimeException) {
      // An interrupted wait (a cancelled coroutine, say) ends as itself, not as a failed release
      if (Thread.currentThread().isInterrupted) throw e
      logger.warn(e) { "Couldn't release $barrierPath; its waiters stay parked" }
      recordException(EtcdRecipeRuntimeException("Couldn't release $barrierPath; its waiters stay parked", e))
      false
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

    checkCloseNotCalled()

    // Waiters register, and are counted, under their round: keys an earlier round hasn't
    // cleaned up yet can't trip this one
    val round = joinRound(uniqueToken)
    val myWaitingPath = roundPath(round).appendToPath(uniqueToken)
    val waitingPrefix = roundPath(round).ensureSuffix("/")

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
      when {
        // The round already tripped: /ready is gone, or names a later round
        currentRound() != round -> {
          closeKeepAlive()
        }

        // Release the round before leaving it: a tripper that left first and then failed to
        // delete /ready would strand everyone else
        client.getChildCount(roundPath(round), resilience.rpc) >= memberCount -> {
          if (releaseRound(round)) closeKeepAlive()
        }
      }
    }

    // checkWaiterCount() off the waiter's thread, where a failed read has no caller to reach
    fun recheck() {
      try {
        checkWaiterCount()
      } catch (e: EtcdRecipeRuntimeException) {
        recordException(e)
      }
    }

    // Register a cancellation hook so close() can unblock this waiter.
    val cancelWait: () -> Unit = {
      cancelled.set(true)
      closeKeepAlive()
    }
    activeWaiters += cancelWait
    // close() sets closeCalled before doClose() runs the registered hooks, so a close()
    // that slipped in after the check above but before this registration is seen here.
    if (closeCalled.load()) cancelWait()

    try {
      // The waiting key is bound to a self-healing lease: if it expires while the
      // waiter is parked (partition longer than the TTL), the healer re-registers
      // it so the barrier can still trip. Healing stops once the barrier lifted or
      // the wait was cancelled (keepAliveClosed). A heal-time CAS loss means the
      // key unexpectedly exists — ownership is not reclaimed.
      val healer =
        try {
          withRecipeLoggingContext {
            client.selfHealingKeepAlive(
              leaseTtlSecs.seconds,
              resilience.lease,
              leaseListener = { event -> onWaiterLeaseEvent(event) },
              rpc = resilience.rpc,
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
          }
        } catch (e: EtcdRecipeRuntimeException) {
          // A close() that lands before the establish hook runs (during the ready CAS
          // or the lease grant) makes the hook decline: that is a cancellation, not a
          // lost CAS. The healer has already revoked its lease either way. Only a
          // declined establish is a lost CAS; any other failure (an unreachable etcd, a
          // refused grant, an interrupt) propagates as itself.
          if (cancelled.get()) return false
          if (e !is EstablishDeclinedException) throw e
          logger.debug(e) { "Waiting-path CAS lost for $myWaitingPath" }
          throw EtcdRecipeException("Failed to set waitingPath", e)
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
            val watchFailure = AtomicReference<Throwable?>(null)

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
                      recheck()
                    }

                    is WatchRecoveryEvent.Failed -> {
                      val cause = event.cause
                        ?: EtcdRecipeRuntimeException("Watch on $barrierPath abandoned while waiting on barrier")
                      watchFailure.store(cause)
                      recordException(cause)
                      closeKeepAlive()
                    }

                    is WatchRecoveryEvent.Suspended -> {
                      // jetcd (transient) or the recovery loop (fatal) is already on it
                    }
                  }
                }
              }

            withRecipeLoggingContext {
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
                        key.startsWith(waitingPrefix) && watchEvent.eventType == PUT -> recheck()
                        key == readyPath && watchEvent.eventType == DELETE -> closeKeepAlive()
                      }
                    }
                },
              ) {
                // Check one more time in case watch missed the delete just after last check
                checkWaiterCount()

                val signalled = keepAliveClosed.waitUntilTrueWithInterruption(timeout)
                // A timeout stops counting this waiter: closeKeepAlive() deletes its key
                if (!signalled) closeKeepAlive()

                watchFailure.load()?.let { cause ->
                  throw EtcdRecipeRuntimeException("Barrier watch on $barrierPath failed while waiting", cause)
                }

                // Distinguish natural completion from cancellation.
                signalled && !cancelled.get()
              }
            }
          }
    } finally {
      activeWaiters -= cancelWait
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
    activeWaiters.forEach { cancelWait -> cancelWait() }
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
