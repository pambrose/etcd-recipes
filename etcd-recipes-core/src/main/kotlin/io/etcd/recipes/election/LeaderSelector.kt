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

package io.etcd.recipes.election

import com.pambrose.common.concurrent.BooleanMonitor
import com.pambrose.common.time.timeUnitToDuration
import com.pambrose.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.support.Observers
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import io.etcd.recipes.common.EstablishDeclinedException
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.DEFAULT_TTL_SECS
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildrenValues
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.isLeaseNotFound
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.leaseRevoke
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.selfHealingKeepAlive
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.etcd.recipes.election.LeaderSelector.Companion.defaultClientId
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.stub.StreamObserver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@JvmOverloads
fun <T> withLeaderSelector(
  client: Client,
  electionPath: String,
  listener: LeaderSelectorListener,
  leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  userExecutor: Executor? = null,
  clientId: String = defaultClientId(),
  receiver: LeaderSelector.() -> T,
): T = LeaderSelector(client, electionPath, listener, leaseTtlSecs, userExecutor, clientId).use { it.receiver() }

@JvmOverloads
fun <T> withLeaderSelector(
  client: Client,
  electionPath: String,
  takeLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
  relinquishLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
  leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  executorService: ExecutorService? = null,
  clientId: String = defaultClientId(),
  receiver: LeaderSelector.() -> T,
): T =
  LeaderSelector(
    client,
    electionPath,
    takeLeadershipBlock,
    relinquishLeadershipBlock,
    leaseTtlSecs,
    executorService,
    clientId,
  ).use { it.receiver() }

// For Java clients
class LeaderSelector
@JvmOverloads
constructor(
  client: Client,
  val electionPath: String,
  private val listener: LeaderSelectorListener,
  val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  private val userExecutor: Executor? = null,
  val clientId: String = defaultClientId(),
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  private val interruptOnLeaseLoss: Boolean = true,
) : EtcdConnector(client, resilience) {
  // For Kotlin clients
  @JvmOverloads
  constructor(
    client: Client,
    electionPath: String,
    takeLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
    relinquishLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
    leaseTtlSecs: Long = DEFAULT_TTL_SECS,
    executorService: ExecutorService? = null,
    clientId: String = defaultClientId(),
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    interruptOnLeaseLoss: Boolean = true,
  ) :
    this(
      client,
      electionPath,
      object : LeaderSelectorListener {
        override fun takeLeadership(selector: LeaderSelector) {
          takeLeadershipBlock.invoke(selector)
        }

        override fun relinquishLeadership(selector: LeaderSelector) {
          relinquishLeadershipBlock.invoke(selector)
        }
      },
      leaseTtlSecs,
      executorService,
      clientId,
      resilience,
      interruptOnLeaseLoss,
    )

  // Runs the candidacy: every election attempt and the term, on one thread. The leader-key
  // watch and the participation lease run on their own internal threads, so a user executor
  // needs only one free thread.
  private var executor: Executor = userExecutor ?: Executors.newSingleThreadExecutor()
  private val terminateWatch = BooleanMonitor(false)
  private val terminateKeepAlive = BooleanMonitor(false)
  private val leadershipComplete = BooleanMonitor(false)
  private val electedLeader = AtomicBoolean(false)
  private val startCallLock = Any()
  private val startCallAllowed = AtomicBoolean(true)
  private val leaderPath = ElectionPaths.leaderKey(electionPath)

  // Wakes the candidacy loop to run for leader: the leader key was deleted, a watch recovery
  // may have missed that, or the selector is finishing. The watch only ever signals here, so
  // no election attempt or term runs on the watch dispatcher.
  private val attemptSignals = LinkedBlockingQueue<Unit>()

  // Step-down machinery: set for the duration of a leadership hold so a fatal
  // keep-alive event (lease gone) can end leadership from the observer thread.
  private val leadershipThreadRef = AtomicReference<Thread?>(null)
  private val leadershipLeaseId = AtomicLong(-1L)
  private val leaseLostDuringLeadership = AtomicBoolean(false)

  init {
    require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    require(leaseTtlSecs > 0) { "Lease TTL must be > 0" }
  }

  override val exceptionContext get() = "LeaderSelector[$electionPath]"

  val isLeader get() = electedLeader.load()

  val isFinished get() = leadershipComplete.get()

  /**
   * Starts this candidacy: runs for leader now and whenever the leader key is deleted, until a
   * term completes or [close]. Returns once the leader-key watch is established; throws
   * [EtcdRecipeRuntimeException] when it can't be (a closed client, an unreachable etcd), or when
   * interrupted while waiting, in which case the interrupt flag is restored.
   */
  @Suppress("TooGenericExceptionCaught")
  fun start(): LeaderSelector {
    synchronized(startCallLock) {
      if (!startCallAllowed.load())
        throw EtcdRecipeRuntimeException("Previous call to start() not complete")

      // Re-create the internal executor if a previous close() shut it down,
      // so the instance can be re-used across start()/close() cycles.
      if (userExecutor == null && (executor as ExecutorService).isShutdown)
        executor = Executors.newSingleThreadExecutor()

      terminateWatch.set(false)
      terminateKeepAlive.set(false)
      leadershipComplete.set(false)
      startThreadComplete.set(false)
      startCalled.store(true)
      closeCalled.store(false)
      electedLeader.store(false)
      startCallAllowed.store(false)
      attemptSignals.clear()
      resetConnectionState()
    }

    val watchReady = CompletableFuture<Unit>()
    val watchThread = thread(name = "etcd-election-watch", isDaemon = true) { runLeaderWatch(watchReady) }
    val participationThread = thread(name = "etcd-election-participation", isDaemon = true) { runParticipation() }
    val helpers = listOf(watchThread, participationThread)
    try {
      watchReady.get()
      executor.execute { withRecipeLoggingContext { runCandidacy(helpers) } }
    } catch (e: Throwable) {
      // A failed or interrupted watch setup, or e.g. a shut-down user executor rejecting the candidacy
      abortStart(helpers)
      throw startFailure(e)
    }
    return this
  }

  private fun startFailure(e: Throwable): Throwable =
    when (e) {
      is InterruptedException -> {
        Thread.currentThread().interrupt()
        EtcdRecipeRuntimeException("Interrupted while starting the election on $electionPath", e)
      }

      is ExecutionException -> {
        EtcdRecipeRuntimeException("Couldn't watch the leader key of $electionPath", e.cause ?: e)
      }

      else -> {
        e
      }
    }

  // Undoes a start() that failed before its candidacy began, so the selector stays closable
  // and can be started again.
  private fun abortStart(helpers: List<Thread>) {
    markLeadershipComplete()
    helpers.forEach { it.join(HELPER_JOIN_MILLIS) }
    synchronized(startCallLock) {
      startCallAllowed.store(true)
      startThreadComplete.set(true)
    }
  }

  // Watches the leader key for deletions, which only signal the candidacy loop. Anchored just
  // past a read of the key, so a deletion that lands while the watch is being set up is still
  // delivered. The leader key's DELETE is this node's only re-election trigger, so a recovery
  // that may have missed one (a compaction resync, or a death before any event) signals too.
  @Suppress("TooGenericExceptionCaught")
  private fun runLeaderWatch(watchReady: CompletableFuture<Unit>) {
    withRecipeLoggingContext {
      try {
        val anchor = client.getResponse(leaderPath, rpc = resilience.rpc).header.revision + 1
        val recoveryListener =
          WatchRecoveryListener { event ->
            withRecipeLoggingContext {
              reportRecoveryEvent(event)
              when (event) {
                is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
                  signalAttempt()
                }

                is WatchRecoveryEvent.Failed -> {
                  val cause = event.cause
                    ?: EtcdRecipeRuntimeException("Watch on $leaderPath abandoned; no further re-election attempts")
                  logger.error(cause) { "Leader watch on $leaderPath abandoned" }
                  recordException(cause)
                }

                is WatchRecoveryEvent.Suspended -> {
                  // jetcd (transient) or the recovery loop (fatal) is already on it
                }
              }
            }
          }
        client.withWatcher(
          leaderPath,
          watchOption {
            withNoPut(true)
            withRevision(anchor)
          },
          resilience.watch,
          recoveryListener,
          resyncWith = null,
          { watchResponse -> if (watchResponse.events.any { it.eventType == DELETE }) signalAttempt() },
        ) {
          watchReady.complete(Unit)
          terminateWatch.waitUntilTrue()
        }
      } catch (e: Throwable) {
        logger.error(e) { "Leader watch on $leaderPath failed" }
        recordException(e)
        watchReady.completeExceptionally(e)
      }
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun runParticipation() {
    withRecipeLoggingContext {
      try {
        advertiseParticipation()
      } catch (e: Throwable) {
        logger.error(e) { "In advertiseParticipation()" }
        recordException(e)
      }
    }
  }

  private fun signalAttempt() {
    attemptSignals.offer(Unit)
  }

  // The candidacy: runs for leader now and on each signal until a term completes or the
  // selector finishes. Every attempt and the term run here, one at a time, so a term never
  // overlaps another (a deletion signalled while a term is unwinding just ends the loop) and
  // close() waits for the term however it was won. An attempt that fails rather than loses
  // (a refused grant, a transaction that timed out) is retried, paced by the watch retry
  // policy; otherwise no deletion would ever come to trigger another, and the election could
  // stay leaderless.
  @Suppress("TooGenericExceptionCaught")
  private fun runCandidacy(helpers: List<Thread>) {
    try {
      val backoff = AttemptBackoff()
      var lease: LeaseGrantResponse? = null
      signalAttempt()
      while (lease == null && awaitAttemptSignal(backoff.delay)) {
        lease = attemptOnce(backoff)
      }
      lease?.let { holdLeadership(it) }
    } catch (e: InterruptedException) {
      logger.debug(e) { "Candidacy on $electionPath interrupted" }
    } catch (e: Throwable) {
      logger.error(e) { "In the candidacy on $electionPath" }
      recordException(e)
    } finally {
      markLeadershipComplete()
      helpers.forEach { it.join(HELPER_JOIN_MILLIS) }
      // Both flags flip under startCallLock so a new start() can't interleave and have its
      // reset overwritten.
      synchronized(startCallLock) {
        startCallAllowed.store(true)
        startThreadComplete.set(true)
      }
    }
  }

  // Waits for a signal to run for leader, or for [retryDelay] after a failed attempt. Coalesces
  // queued signals (one attempt answers them all). False once the candidacy is finishing.
  private fun awaitAttemptSignal(retryDelay: Duration?): Boolean {
    if (retryDelay ==
      null
    )
      attemptSignals.take()
      else
      attemptSignals.poll(retryDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    attemptSignals.clear()
    return !leadershipComplete.get()
  }

  // One election attempt: the lease on a win, else null (lost, or failed and to be retried).
  @Suppress("TooGenericExceptionCaught")
  private fun attemptOnce(backoff: AttemptBackoff): LeaseGrantResponse? =
    try {
      claimLeadership().also { backoff.reset() }
    } catch (e: Throwable) {
      val delay = backoff.failed()
      logger.warn(e) {
        "Election attempt on $electionPath failed; ${delay?.let {
          "retrying in $it"
        } ?: "retrying on the next deletion"}"
      }
      recordException(e)
      null
    }

  // Paces retries of failed election attempts by the watch retry policy.
  private inner class AttemptBackoff {
    private var failures = 0
    private var failingSince: TimeMark? = null

    /** The wait before the next attempt, or null to wait for the next deletion signal. */
    var delay: Duration? = null
      private set

    fun reset() {
      failures = 0
      failingSince = null
      delay = null
    }

    fun failed(): Duration? {
      failures += 1
      val since = failingSince ?: TimeSource.Monotonic.markNow().also { failingSince = it }
      delay = resilience.watch.retryPolicy.nextDelay(failures, since.elapsedNow())
      return delay
    }
  }

  @Throws(InterruptedException::class)
  fun waitOnLeadershipComplete(): Boolean = waitOnLeadershipComplete(Long.MAX_VALUE.days)

  @Throws(InterruptedException::class)
  fun waitOnLeadershipComplete(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = waitOnLeadershipComplete(timeUnitToDuration(timeout, timeUnit))

  @Throws(InterruptedException::class)
  fun waitOnLeadershipComplete(timeout: Duration): Boolean {
    checkStartCalled()
    checkCloseNotCalled()
    // One deadline covers both waits: first the term itself, then the start worker's
    // unwind (so a start() re-used without close() sees the finished candidacy). An
    // untimed wait on startThreadComplete here made a standby's timed wait block until
    // it won and finished a term, or was closed.
    val started = TimeSource.Monotonic.markNow()
    if (!leadershipComplete.waitUntilTrueWithInterruption(timeout)) return false
    return startThreadComplete.waitUntilTrueWithInterruption((timeout - started.elapsedNow()).coerceAtLeast(ZERO))
  }

  // Blocking form of [isFinished]: waits until leadership completes (set by close()
  // or by relinquishing). Unlike waitOnLeadershipComplete it acquires no instance
  // monitor and does not wait on startThreadComplete, so it is safe to call from
  // inside takeLeadership as a stop signal — a close() from another thread flips
  // leadershipComplete and releases this wait.
  @Throws(InterruptedException::class)
  fun waitUntilFinished(): Boolean = waitUntilFinished(Long.MAX_VALUE.days)

  @Throws(InterruptedException::class)
  fun waitUntilFinished(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = waitUntilFinished(timeUnitToDuration(timeout, timeUnit))

  @Throws(InterruptedException::class)
  fun waitUntilFinished(timeout: Duration): Boolean = leadershipComplete.waitUntilTrueWithInterruption(timeout)

  private fun markLeadershipComplete() {
    terminateWatch.set(true)
    terminateKeepAlive.set(true)
    leadershipComplete.set(true)
    signalAttempt() // wake the candidacy loop so it can finish
  }

  override fun doClose() {
    if (startCalled.load()) {
      markLeadershipComplete()
      // close() from inside takeLeadership runs on the thread holding the term: waiting
      // for the start worker would wait on itself. The term unwinds once takeLeadership
      // returns. (The start worker's finally re-allows start().)
      if (Thread.currentThread() !== leadershipThreadRef.load())
        startThreadComplete.waitUntilTrue()
    }

    if (userExecutor == null) (executor as ExecutorService).shutdown()
  }

  @Throws(EtcdRecipeException::class)
  // internal (not private) so lease-cleanup behavior can be unit-tested directly.
  internal fun advertiseParticipation() {
    val path = ElectionPaths.participantKey(electionPath, clientId)

    // Wait until key goes away when previous keep alive finishes
    val attemptCount = leaseTtlSecs * 2
    for (i in 0 until attemptCount) {
      if (!client.isKeyPresent(path, resilience.rpc)) {
        break
      }

      // Only sleep when another attempt will follow; on the last iteration just log
      // the exhaustion and let the loop end (the CAS below then fails and throws as
      // before). This also avoids a redundant final ~1s sleep past the intended bound.
      if (i == attemptCount - 1) {
        logger.error { "Exhausted wait for deletion of participation key $path" }
      } else {
        sleep(1.seconds)
      }
    }

    // Participation is self-healing: if its lease expires (partition longer than
    // the TTL), the healer re-grants it and re-registers this candidate, so the
    // node stays visible in getParticipants() instead of silently disappearing.
    val healer =
      try {
        client.selfHealingKeepAlive(
          leaseTtlSecs.seconds,
          resilience.lease,
          leaseListener = { event -> onParticipationLeaseEvent(event) },
          rpc = resilience.rpc,
        ) { lease ->
          client.transaction(resilience.rpc) {
            If(path.doesNotExist)
            Then(path.setTo(clientId, putOption { withLeaseId(lease.id) }))
          }.isSucceeded
        }
      } catch (e: EstablishDeclinedException) {
        // Initial CAS lost (the healer already revoked its lease). Any other failure
        // (an unreachable etcd, a refused grant) is not a lost CAS and propagates with
        // its cause.
        logger.debug(e) { "Participation CAS lost for $path" }
        throw EtcdRecipeException("Participation registration failed [$path]", e)
      }

    // Run until closed; closing the healer revokes the participation lease promptly
    // (#7) so the participant key is evicted on relinquish instead of lingering
    // until TTL (which is what forces the pre-CAS wait loop above).
    healer.use { terminateKeepAlive.waitUntilTrue() }
  }

  private fun onParticipationLeaseEvent(event: LeaseEvent) {
    withRecipeLoggingContext {
      reportLeaseEvent(event)
      when (event) {
        is LeaseEvent.Suspended -> recordException(event.cause)

        is LeaseEvent.Expired -> recordException(
          event.cause ?: EtcdRecipeRuntimeException("Participation lease for $clientId expired; healing"),
        )

        is LeaseEvent.Failed -> recordException(
          event.cause ?: EtcdRecipeRuntimeException("Participation lease healing for $clientId abandoned"),
        )

        is LeaseEvent.Restored -> logger.info {
          "Participation lease for $clientId healed: ${event.oldLeaseId} -> ${event.newLeaseId}"
        }
      }
    }
  }

  // Phase 1: the leader-key CAS. Returns the lease this node now leads under, or null when
  // another candidate leads (no lease is left behind). Throws when the attempt failed rather
  // than lost, after revoking its lease: a transaction whose outcome is unknown may have
  // committed, and revoking the lease removes the key it would have written.
  private fun claimLeadership(): LeaseGrantResponse? =
    // Someone leads: wait for the deletion rather than spend a lease grant on a CAS that can't win
    if (client.isKeyPresent(leaderPath, resilience.rpc)) null else casForLeadership()

  @Suppress("TooGenericExceptionCaught")
  private fun casForLeadership(): LeaseGrantResponse? {
    // Create unique token to avoid collision from clients with same id
    val uniqueToken = ElectionPaths.leaderToken(clientId)
    val granted = client.leaseGrant(leaseTtlSecs.seconds, resilience.rpc)
    val won =
      try {
        client.transaction(resilience.rpc) {
          If(leaderPath.doesNotExist)
          Then(leaderPath.setTo(uniqueToken, putOption { withLeaseId(granted.id) }))
        }.isSucceeded
      } catch (e: Throwable) {
        client.leaseRevoke(granted, resilience.rpc)
        throw e
      }
    if (!won) {
      // Lost the CAS: revoke the lease so it does not linger in etcd until its TTL.
      client.leaseRevoke(granted, resilience.rpc)
      return null
    }
    electedLeader.store(true)
    resilience.metrics.incrementLeadershipTransition(electionPath, becameLeader = true)
    return granted
  }

  // Phase 2: holds leadership until it is relinquished (takeLeadership returns) or the lease
  // is lost (step-down), on the candidacy thread, which close() waits for.
  //
  // Leadership is intentionally NOT self-healed: an expired lease means etcd
  // deleted the leader key and another candidate may already lead — reclaiming
  // would race the new leader. A fatal keep-alive event (stream completed, or
  // NOT_FOUND "requested lease not found") instead steps this leader down.
  @Suppress("TooGenericExceptionCaught")
  private fun holdLeadership(lease: LeaseGrantResponse) {
    leadershipThreadRef.store(Thread.currentThread())
    leadershipLeaseId.store(lease.id)
    leaseLostDuringLeadership.store(false)
    val registration = client.leaseClient.keepAlive(lease.id, leadershipKeepAliveObserver(lease.id))
    try {
      var takeLeadershipError: Throwable? = null
      try {
        listener.takeLeadership(this)
      } catch (e: Throwable) {
        if (!leaseLostDuringLeadership.load()) takeLeadershipError = e
      }
      // Clear a step-down interrupt that may have landed after (or instead of)
      // unblocking takeLeadership, so cleanup below is not disrupted by it.
      if (leaseLostDuringLeadership.load()) Thread.interrupted()

      // Leadership is over (relinquished or stepped down): always notify, even when
      // takeLeadership threw, so the listener can release its resources. (Pre-0.12
      // a throw skipped relinquishLeadership.)
      resilience.metrics.incrementLeadershipTransition(electionPath, becameLeader = false)
      try {
        listener.relinquishLeadership(this)
      } catch (e: Throwable) {
        logger.error(e) { "In relinquishLeadership()" }
        recordException(e)
      }
      takeLeadershipError?.let { throw it }
    } catch (e: Throwable) {
      logger.error(e) { "In takeLeadership()" }
      recordException(e)
    } finally {
      registration.close()
      // Revoke the leadership lease promptly on relinquish (#7) instead of at TTL.
      client.leaseRevoke(lease, resilience.rpc)
      leadershipThreadRef.store(null)
      electedLeader.store(false)
      markLeadershipComplete()
    }
  }

  // Discriminates leadership keep-alive stream events: fatal (stream completed =
  // lease outlived its TTL unrenewed, or NOT_FOUND = lease gone) steps the leader
  // down; anything else is transient — jetcd restarts the stream itself.
  private fun leadershipKeepAliveObserver(leaseId: Long): StreamObserver<LeaseKeepAliveResponse> {
    val suspended = AtomicBoolean(false)
    return Observers.builder<LeaseKeepAliveResponse>()
      .onNext { next ->
        logger.debug { "Leadership keep-alive resp: $next" }
        // The first renewal after a transient error: jetcd restarted the stream by itself
        if (suspended.compareAndSet(true, false)) reportLeaseEvent(LeaseEvent.Restored(leaseId, leaseId))
      }
      .onError { e ->
        if (e.isLeaseNotFound()) {
          stepDownFromLeadership(e)
        } else {
          suspended.store(true)
          recordException(e)
          reportLeaseEvent(LeaseEvent.Suspended(leaseId, e))
        }
      }
      .onCompleted { stepDownFromLeadership(null) }
      .build()
  }

  // Ends leadership when the lease is gone: isLeader turns false immediately, the
  // finished monitors are flipped so waitUntilFinished()/waitOnLeadershipComplete()
  // release, and (when [interruptOnLeaseLoss]) the takeLeadership thread is
  // interrupted in case it is parked in its own code where only an interrupt
  // reaches it. Runs on jetcd's lease callback thread — no blocking work here.
  // internal (not private) so step-down mechanics can be driven directly in tests.
  internal fun stepDownFromLeadership(cause: Throwable?) {
    if (!electedLeader.load()) return
    if (!leaseLostDuringLeadership.compareAndSet(false, true)) return

    logger.warn(cause) { "Leadership lease lost for $clientId; stepping down" }
    recordException(cause ?: EtcdRecipeRuntimeException("Leadership lease expired; stepping down"))
    electedLeader.store(false)
    reportLeaseEvent(LeaseEvent.Expired(leadershipLeaseId.load(), cause))

    // Release monitor-parked holders first; then interrupt for user code parked
    // elsewhere (sleep/IO). waitUntilFinished callers wake without an interrupt.
    leadershipComplete.set(true)
    if (interruptOnLeaseLoss) leadershipThreadRef.load()?.interrupt()
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    // How long a finishing candidacy waits for its watch and participation threads to end
    private const val HELPER_JOIN_MILLIS = 10_000L

    internal fun defaultClientId() = defaultClientId(LeaderSelector::class.simpleName!!)

    // The clientId of the election's current leader, or null when there is none
    private fun Client.currentLeaderId(
      electionPath: String,
      rpc: RpcResilience = RpcResilience.DEFAULT,
    ): String? = getValue(ElectionPaths.leaderKey(electionPath), rpc)?.asString?.let(ElectionPaths::stripLeaderClientId)

    @JvmStatic
    @JvmOverloads
    fun getParticipants(
      client: Client,
      electionPath: String,
      rpc: RpcResilience = RpcResilience.DEFAULT,
    ): List<Participant> {
      require(electionPath.isNotEmpty()) { "Election path cannot be empty" }

      val participants: MutableList<Participant> = []
      val leader = client.currentLeaderId(electionPath, rpc) ?: ""
      client.getChildrenValues(ElectionPaths.participantsPath(electionPath), rpc = rpc).map { it.asString }
        .forEach { participants += Participant(it, leader == it) }
      return participants
    }

    // A leader PUT/DELETE can be lost while the watch stream is fatally dead:
    // always for a compaction resync, and for a plain resubscribe only when nothing
    // had ever been observed (resumeRevision 0 — no revision to replay from). In
    // those cases re-read the leader key and replay the current state to the
    // listener.
    @Suppress("TooGenericExceptionCaught")
    private fun reportLeaderRecoveryListener(
      client: Client,
      electionPath: String,
      listener: LeaderListener,
    ): WatchRecoveryListener =
      WatchRecoveryListener { event ->
        try {
          when (event) {
            is WatchRecoveryEvent.Resynced,
            is WatchRecoveryEvent.Resubscribed,
            -> {
              val gapPossible = event !is WatchRecoveryEvent.Resubscribed || event.resumeRevision == 0L
              if (gapPossible) {
                val leader = client.currentLeaderId(electionPath)
                if (leader != null) listener.takeLeadership(leader) else listener.relinquishLeadership()
              }
            }

            is WatchRecoveryEvent.Failed -> {
              listener.onError(
                event.cause ?: EtcdRecipeRuntimeException("Leader watch on $electionPath abandoned"),
              )
            }

            is WatchRecoveryEvent.Suspended -> {
              // jetcd (transient) or the recovery loop (fatal) is already on it
            }
          }
        } catch (e: Throwable) {
          logger.error(e) { "Exception in reportLeader() recovery" }
          listener.onError(e)
        }
      }

    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun reportLeader(
      urls: List<String>,
      electionPath: String,
      listener: LeaderListener,
      executor: Executor,
    ): CountDownLatch {
      require(urls.isNotEmpty()) { "URLs cannot be empty" }
      require(electionPath.isNotEmpty()) { "Election path cannot be empty" }

      val terminateListener = CountDownLatch(1)
      executor.execute {
        connectToEtcd(urls) { client ->
          val recoveryListener = reportLeaderRecoveryListener(client, electionPath, listener)

          client.withWatcher(
            ElectionPaths.leaderKey(electionPath),
            WatchOption.DEFAULT,
            WatchResilience.DEFAULT,
            recoveryListener,
            resyncWith = null,
            block = { watchResponse ->
              for (event in watchResponse.events) {
                try {
                  when (event.eventType) {
                    PUT -> listener.takeLeadership(ElectionPaths.stripLeaderClientId(event.keyValue.value.asString))
                    DELETE -> listener.relinquishLeadership()
                    UNRECOGNIZED -> logger.error { "Unrecognized error with $electionPath watch" }
                    else -> logger.error { "Unknown error with $electionPath watch" }
                  }
                } catch (e: Throwable) {
                  logger.error(e) { "Exception in reportLeader()" }
                  listener.onError(e)
                }
              }
            },
          ) {
            terminateListener.await()
          }
        }
      }
      return terminateListener
    }
  }
}
