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
import com.pambrose.common.util.randomId
import com.pambrose.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.support.Observers
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.DEFAULT_TTL_SECS
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildrenValues
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.isKeyNotPresent
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

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

  private var executor: Executor = userExecutor ?: Executors.newFixedThreadPool(3)
  private val terminateWatch = BooleanMonitor(false)
  private val terminateKeepAlive = BooleanMonitor(false)
  private val leadershipComplete = BooleanMonitor(false)
  private val attemptLeadership = BooleanMonitor(true)
  private val electedLeader = AtomicBoolean(false)
  private val startCallLock = Any()

  // Serializes the leadership-claim section of attemptToBecomeLeader (CAS + guard
  // resets) across the start() worker and the watch-dispatcher thread. Deliberately
  // NOT the instance monitor: close() is @Synchronized on the instance, so holding
  // the instance monitor across takeLeadership (the previous @Synchronized) made a
  // close() during active leadership deadlock. This lock is released before the
  // takeLeadership block runs, so close() can flip the termination monitors meanwhile.
  private val electionLock = Any()
  private val startCallAllowed = AtomicBoolean(true)
  private val leaderPath = electionPath.withLeaderSuffix

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

  @Suppress("TooGenericExceptionCaught", "LongMethod")
  fun start(): LeaderSelector {
    val electionSetup = BooleanMonitor(false)

    synchronized(startCallLock) {
      if (!startCallAllowed.load())
        throw EtcdRecipeRuntimeException("Previous call to start() not complete")

      // Re-create the internal executor if a previous close() shut it down,
      // so the instance can be re-used across start()/close() cycles.
      if (userExecutor == null && (executor as ExecutorService).isShutdown)
        executor = Executors.newFixedThreadPool(3)

      terminateWatch.set(false)
      terminateKeepAlive.set(false)
      leadershipComplete.set(false)
      startThreadComplete.set(false)
      attemptLeadership.set(true)
      startCalled.store(true)
      closeCalled.store(false)
      electedLeader.store(false)
      startCallAllowed.store(false)
    }

    executor.execute {
      try {
        val watchStarted = BooleanMonitor(false)
        val watchComplete = BooleanMonitor(false)
        val advertiseComplete = BooleanMonitor(false)

        executor.execute {
          try {
            val watchOption = watchOption { withNoPut(true) }

            // The leader-key DELETE is this node's only re-election trigger. If it
            // is lost while the watch stream is fatally dead (compaction resync, or
            // a death before any event), the node would never run for leader again —
            // so after each recovery, re-probe the leader key and run if it is gone.
            // attemptToBecomeLeader CAS-guards against a concurrent winner.
            val recoveryListener =
              WatchRecoveryListener { event ->
                reportRecoveryEvent(event)
                when (event) {
                  is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
                    if (client.isKeyNotPresent(leaderPath, resilience.rpc))
                      attemptToBecomeLeader(client)
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

            client.withWatcher(
              leaderPath,
              watchOption,
              resilience.watch,
              recoveryListener,
              resyncWith = null,
              { watchResponse ->
                for (event in watchResponse.events) {
                  if (event.eventType == DELETE) {
                    // Run for leader whenever leader key is deleted
                    attemptToBecomeLeader(client)
                  }
                }
              },
            ) {
              watchStarted.set(true)
              terminateWatch.waitUntilTrue()
            }
          } catch (e: Throwable) {
            logger.error(e) { "In withWatchClient()" }
            recordException(e)
          } finally {
            watchComplete.set(true)
          }
        }

        executor.execute {
          try {
            advertiseParticipation()
          } catch (e: Throwable) {
            logger.error(e) { "In advertiseParticipation()" }
            recordException(e)
          } finally {
            advertiseComplete.set(true)
          }
        }

        // Wait for the watcher to start
        watchStarted.waitUntilTrue()

        electionSetup.set(true)

        // Clients should run for leader in case they are the first to run
        attemptToBecomeLeader(client)

        leadershipComplete.waitUntilTrue()
        watchComplete.waitUntilTrue()
        advertiseComplete.waitUntilTrue()
      } catch (e: Throwable) {
        logger.error(e) { "In start()" }
        recordException(e)
      } finally {
        startThreadComplete.set(true)
      }
    }

    electionSetup.waitUntilTrue()

    return this
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
    // Check startThreadComplete here in case start() was re-used without a call to close()
    startThreadComplete.waitUntilTrueWithInterruption()
    return leadershipComplete.waitUntilTrueWithInterruption(timeout)
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
  }

  override fun doClose() {
    checkStartCalled()

    markLeadershipComplete()
    startThreadComplete.waitUntilTrue()

    if (userExecutor == null) (executor as ExecutorService).shutdown()
  }

  @Throws(EtcdRecipeException::class)
  // internal (not private) so lease-cleanup behavior can be unit-tested directly.
  internal fun advertiseParticipation() {
    val path = electionPath.withParticipationSuffix.appendToPath(clientId)

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
        ) { lease ->
          client.transaction(resilience.rpc) {
            If(path.doesNotExist)
            Then(path.setTo(clientId, putOption { withLeaseId(lease.id) }))
          }.isSucceeded
        }
      } catch (e: EtcdRecipeRuntimeException) {
        // Initial CAS lost (the healer already revoked its lease).
        logger.debug(e) { "Participation CAS lost for $path" }
        throw EtcdRecipeException("Participation registration failed [$path]")
      }

    // Run until closed; closing the healer revokes the participation lease promptly
    // (#7) so the participant key is evicted on relinquish instead of lingering
    // until TTL (which is what forces the pre-CAS wait loop above).
    try {
      terminateKeepAlive.waitUntilTrue()
    } finally {
      healer.close()
    }
  }

  private fun onParticipationLeaseEvent(event: LeaseEvent) {
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

  // This will not return until election failure or leader surrenders leadership after being elected
  @Suppress("ReturnCount", "TooGenericExceptionCaught")
  private fun attemptToBecomeLeader(client: Client): Boolean {
    // Phase 1 — claim leadership under electionLock (NOT the instance monitor) so
    // concurrent candidates (start() worker vs watch-dispatcher) are serialized
    // without blocking close(). Returns the granted lease on a win, or returns
    // false on every losing path after revoking its own lease.
    val lease =
      synchronized(electionLock) {
        if (isLeader || !attemptLeadership.get()) {
          return false
        }

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$clientId:${randomId(TOKEN_LENGTH)}"

        // Prime lease to give keepAliveWith a chance to get started; route through
        // the common/ extension layer rather than reaching into jetcd directly.
        val granted = client.leaseGrant(leaseTtlSecs.seconds, resilience.rpc)

        // Check the key name. If it is not found, then set it
        val txn =
          client.transaction(resilience.rpc) {
            If(leaderPath.doesNotExist)
            Then(leaderPath.setTo(uniqueToken, putOption { withLeaseId(granted.id) }))
          }

        // The CAS is authoritative: txn.isSucceeded means this client created the
        // leader key with uniqueToken. (The previous getValue re-read only guarded
        // the tiny window where another client overwrote it between commit and read.)
        if (!txn.isSucceeded) {
          // Failed to become leader: revoke the lease we just created so it does
          // not linger in etcd until TTL. Without this, every losing candidate in
          // an election leaks a lease per turnover.
          client.leaseRevoke(granted, resilience.rpc)
          return false
        }

        // Mark elected inside the lock so a concurrent candidate sees isLeader and bails.
        electedLeader.store(true)
        resilience.metrics.incrementLeadershipTransition(electionPath, becameLeader = true)
        granted
      }

    // Phase 2 — hold leadership with NO lock held, so a close() on another thread
    // can run doClose() -> markLeadershipComplete() and release a takeLeadership
    // that is waiting on isFinished/waitUntilFinished. Exits when leadership is
    // relinquished (takeLeadership returns) or the lease is lost (step-down).
    //
    // Leadership is intentionally NOT self-healed: an expired lease means etcd
    // deleted the leader key and another candidate may already lead — reclaiming
    // would race the new leader. A fatal keep-alive event (stream completed, or
    // NOT_FOUND "requested lease not found") instead steps this leader down.
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
      return !leaseLostDuringLeadership.load()
    } catch (e: Throwable) {
      logger.error(e) { "In attemptToBecomeLeader()" }
      recordException(e)
      return false
    } finally {
      registration.close()
      // Revoke the leadership lease promptly on relinquish (#7) instead of at TTL.
      client.leaseRevoke(lease)
      leadershipThreadRef.store(null)
      // Reset the election guards under the same lock Phase 1 reads them, so a
      // concurrent candidate never observes a half-updated guard set.
      synchronized(electionLock) {
        attemptLeadership.set(false)
        startCallAllowed.store(true)
        electedLeader.store(false)
      }
      // Do this after leadership is complete so the thread does not terminate early
      markLeadershipComplete()
    }
  }

  // Discriminates leadership keep-alive stream events: fatal (stream completed =
  // lease outlived its TTL unrenewed, or NOT_FOUND = lease gone) steps the leader
  // down; anything else is transient — jetcd restarts the stream itself.
  private fun leadershipKeepAliveObserver(leaseId: Long) =
    Observers.builder<LeaseKeepAliveResponse>()
      .onNext { next -> logger.debug { "Leadership keep-alive resp: $next" } }
      .onError { e ->
        if (e.isLeaseNotFound()) {
          stepDownFromLeadership(e)
        } else {
          recordException(e)
          reportLeaseEvent(LeaseEvent.Suspended(leaseId, e))
        }
      }
      .onCompleted { stepDownFromLeadership(null) }
      .build()

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

    private val String.withParticipationSuffix get() = appendToPath("participants")
    private val String.withLeaderSuffix get() = appendToPath("LEADER")

    internal val String.stripUniqueSuffix get() = dropLast(TOKEN_LENGTH + 1)

    internal fun defaultClientId() = EtcdConnector.defaultClientId(LeaderSelector::class.simpleName!!)

    @JvmStatic
    fun getParticipants(
      client: Client,
      electionPath: String,
    ): List<Participant> {
      require(electionPath.isNotEmpty()) { "Election path cannot be empty" }

      val participants = mutableListOf<Participant>()
      val leader = client.getValue(electionPath.withLeaderSuffix)?.asString?.stripUniqueSuffix ?: ""
      client.getChildrenValues(electionPath.withParticipationSuffix).map { it.asString }
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
                val leader = client.getValue(electionPath.withLeaderSuffix)?.asString?.stripUniqueSuffix
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
            electionPath.withLeaderSuffix,
            WatchOption.DEFAULT,
            WatchResilience.DEFAULT,
            recoveryListener,
            resyncWith = null,
            block = { watchResponse ->
              for (event in watchResponse.events) {
                try {
                  when (event.eventType) {
                    PUT -> listener.takeLeadership(event.keyValue.value.asString.stripUniqueSuffix)
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
