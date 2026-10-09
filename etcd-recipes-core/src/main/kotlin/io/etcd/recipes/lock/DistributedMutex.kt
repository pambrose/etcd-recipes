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

package io.etcd.recipes.lock

import com.pambrose.common.time.timeUnitToDuration
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.lock.LockResponse
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.isLeaseNotFound
import io.etcd.recipes.common.isRetriableRpcFailure
import io.etcd.recipes.common.unlock
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A distributed, reentrant mutex on etcd's native lock service (which queues
 * waiters server-side, FIFO by revision, with requireLeader applied by jetcd —
 * a waiter never blocks silently against a partitioned server).
 *
 * Thread-per-acquisition (Curator parity): every non-reentrant acquisition grants
 * its own lease and queues in etcd, so a second thread on this instance waits in
 * the same FIFO as a second process. The acquisition lease is kept alive through
 * the wait and the hold; it is deliberately NOT self-healed — an expired lease
 * means etcd already promoted the next waiter, so the dispossessed thread's hold
 * is *lost* (see [EtcdLock] and [addLockLostListener]) rather than reclaimed.
 *
 * A thread that dies while holding never unlocks; its hold persists until
 * [close] (documented Curator-parity caveat).
 */
class DistributedMutex
  @JvmOverloads
  constructor(
    client: Client,
    val lockPath: String,
    val leaseTtlSecs: Long = DEFAULT_LOCK_TTL_SECS,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    val clientId: String = defaultClientId(DistributedMutex::class.simpleName!!),
    internal val interruptOnLockLoss: Boolean = false,
  ) : EtcdConnector(client, resilience),
    EtcdLock {
  private class LockData(
    val acquisitionLease: AcquisitionLease,
    val ownershipKey: ByteSequence,
    // The acquisition this hold came from: a loss is applied only to the hold it belongs to
    val attempt: Attempt,
    // The revision etcd granted the lock at: past the release (or expiry) of every earlier hold
    val fencingToken: Long,
  ) {
    // Changed by the owner thread; read by a loss on jetcd's lease thread
    @Volatile
    var holdCount = 1
    val acquiredAt: ComparableTimeMark = TimeSource.Monotonic.markNow()
  }

  private enum class Phase { WAITING, HOLDING, DEAD }

  // One in-flight acquisition: the phase machine resolves the race between the
  // keep-alive's fatal callback and the acquirer's win. Exactly one of
  // {retry-as-loser, lockLost} runs.
  private class Attempt(
    val owner: Thread,
  ) {
    val phase = AtomicReference(Phase.WAITING)

    @Volatile
    var future: CompletableFuture<LockResponse>? = null
  }

  private val threadData = ConcurrentHashMap<Thread, LockData>()
  private val dispossessed = ConcurrentHashMap<Thread, Int>()
  private val attempts = CopyOnWriteArrayList<Attempt>()
  private val lockLostListeners = CopyOnWriteArrayList<LockLostListener>()

  init {
    require(lockPath.isNotEmpty()) { "Lock path cannot be empty" }
    require(leaseTtlSecs > 0) { "Lease TTL must be > 0" }
  }

  override val exceptionContext get() = "DistributedMutex[$lockPath]"

  @Throws(InterruptedException::class)
  override fun lock() {
    val timed = !isHeldByCurrentThread // don't time reentrant re-locks
    val start = TimeSource.Monotonic.markNow()
    check(acquire(null)) { "unbounded acquisition returned without the lock" }
    if (timed) resilience.metrics.recordLockWait(lockPath, start.elapsedNow(), acquired = true)
  }

  @Throws(InterruptedException::class)
  override fun tryLock(timeout: Duration): Boolean {
    require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
    val timed = !isHeldByCurrentThread
    val start = TimeSource.Monotonic.markNow()
    val acquired = acquire(TimeSource.Monotonic.markNow() + timeout)
    if (timed) resilience.metrics.recordLockWait(lockPath, start.elapsedNow(), acquired)
    return acquired
  }

  @Throws(InterruptedException::class)
  override fun tryLock(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = tryLock(timeUnitToDuration(timeout, timeUnit))

  override val isHeldByCurrentThread: Boolean get() = threadData.containsKey(Thread.currentThread())

  override val isLocked: Boolean
    get() {
      checkCloseNotCalled()
      // Advisory: any entry under the prefix implies a granted holder (waiters
      // included transiently for the ms until a timed-out attempt's revoke lands).
      return client.getChildCount(lockPath, resilience.rpc) > 0L
    }

  override val holdCount: Int get() = threadData[Thread.currentThread()]?.holdCount ?: 0

  override val fencingToken: Long get() = threadData[Thread.currentThread()]?.fencingToken ?: -1L

  override fun addLockLostListener(listener: LockLostListener) {
    lockLostListeners += listener
  }

  override fun removeLockLostListener(listener: LockLostListener) {
    lockLostListeners -= listener
  }

  // No checkCloseNotCalled: unlocking after close() is legitimate cleanup — the
  // hold was released by close(), so the caller gets false, not a throw.
  @Suppress("ReturnCount")
  override fun unlock(): Boolean {
    val me = Thread.currentThread()
    val data = threadData[me]
    if (data != null) {
      if (data.holdCount > 1) {
        data.holdCount -= 1
        return true
      }
      threadData.remove(me)
      releaseHold(data)
      return true
    }

    val lostHolds = dispossessed[me]
    if (lostHolds != null) {
      if (lostHolds > 1) dispossessed[me] = lostHolds - 1 else dispossessed.remove(me)
      logger.debug { "unlock() on $lockPath after the hold was lost or released by close()" }
      return false
    }

    throw IllegalMonitorStateException("Current thread does not hold the lock on $lockPath")
  }

  @Suppress(
    "ReturnCount",
    "ThrowsCount",
    "LoopWithTooManyJumpStatements",
    "TooGenericExceptionCaught",
    "LongMethod",
    "CyclomaticComplexMethod",
  )
  private fun acquire(deadline: ComparableTimeMark?): Boolean {
    checkCloseNotCalled()
    val me = Thread.currentThread()
    threadData[me]?.let { data ->
      data.holdCount += 1
      return true
    }

    while (true) {
      checkCloseNotCalled()
      if (deadline.hasPassed()) return false

      val attempt = Attempt(me)
      // The lease is kept alive from grant, through the server-side wait, and
      // across the hold; its fatal callback drives both mid-wait aborts and
      // lock-lost while holding. A deadline bounds the grant too.
      val lease =
        try {
          AcquisitionLease(
            client,
            leaseTtlSecs,
            resilience.rpc.within(deadline),
            onTransient = { leaseId, e ->
              recordException(e)
              reportLeaseEvent(LeaseEvent.Suspended(leaseId, e))
            },
            onResumed = { leaseId -> reportLeaseEvent(LeaseEvent.Restored(leaseId, leaseId)) },
            onFatal = { cause -> onAttemptFatal(attempt, cause) },
          )
        } catch (e: EtcdRecipeRuntimeException) {
          if (deadline.hasPassed()) return false // time ran out during the grant
          throw e
        }
      attempts += attempt
      var acquired = false
      try {
        // A close() that ran before this attempt registered found nothing to abort
        if (closeCalled.load()) abortedByClose()
        val future = client.lockClient.lock(lockPath.asByteSequence, lease.leaseId)
        attempt.future = future

        val response =
          try {
            awaitLock(future, deadline)
          } catch (
            @Suppress("SwallowedException") e: TimeoutException,
          ) {
            // The timeout IS the outcome; the finally's revoke is the authoritative abort
            future.cancel(true)
            return false
          } catch (e: InterruptedException) {
            future.cancel(true)
            throw e
          } catch (e: Exception) {
            if (closeCalled.load()) {
              abortedByClose(e)
            }
            // Only lease death mid-wait and transient RPC failures ("no leader", a timeout)
            // are worth another attempt; anything else (permission denied) never heals.
            if (!isRetriableLockFailure(attempt, e)) throw EtcdRecipeRuntimeException("Lock on $lockPath failed", e)
            // Retry with a fresh lease (paced); tryLock stays bounded by the deadline.
            recordException(e)
            pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
            continue
          }

        // Publish the hold BEFORE claiming the phase, so a fatal that lands in the
        // win window always finds the hold to dispossess (or the CAS failure below
        // rolls it back) — never a silently-dead "held" lock.
        val data = LockData(lease, response.key, attempt, response.header.revision)
        threadData[me] = data
        if (attempt.phase.compareAndSet(Phase.WAITING, Phase.HOLDING)) {
          if (closeCalled.load()) {
            // close() landed in the win window: don't hand out a lock on a closed mutex
            threadData.remove(me, data)
            dispossessed.remove(me)
            abortedByClose()
          }
          dispossessed.remove(me)
          acquired = true
          return true
        }
        // The lease died in the win window: roll back and retry as a loser
        threadData.remove(me, data)
        continue
      } finally {
        attempts -= attempt
        if (!acquired) {
          // Revoke is idempotent and safe on an already-dead lease; it deletes any
          // just-granted ownership key and aborts the server-side wait. A bounded
          // acquisition gives it one short attempt, so it returns near its deadline.
          if (deadline == null) lease.close() else lease.closePromptly()
        }
      }
    }
  }

  private fun awaitLock(
    future: CompletableFuture<LockResponse>,
    deadline: ComparableTimeMark?,
  ): LockResponse =
    if (deadline == null) {
      future.get()
    } else {
      val remaining = -deadline.elapsedNow()
      if (remaining <= Duration.ZERO) throw TimeoutException()
      future.get(remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

  // Runs on jetcd's lease callback thread: no blocking RPCs here.
  private fun onAttemptFatal(
    attempt: Attempt,
    cause: Throwable?,
  ) {
    if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
      // Unbind the waiter even when the server is unreachable and the RPC would
      // otherwise never return.
      attempt.future?.completeExceptionally(
        cause ?: EtcdRecipeRuntimeException("Lock lease expired while waiting on $lockPath"),
      )
    } else if (attempt.phase.load() == Phase.HOLDING) {
      lockLost(attempt, cause)
    }
  }

  private fun isRetriableLockFailure(
    attempt: Attempt,
    e: Exception,
  ): Boolean = attempt.phase.load() == Phase.DEAD || e.isLeaseNotFound() || e.isRetriableRpcFailure()

  // Cooperative dispossession (once-guarded by the map removal): state flips,
  // listeners fire, LOST is reported; interruption is opt-in because critical
  // sections are inline user code.
  @Suppress("TooGenericExceptionCaught")
  private fun lockLost(
    attempt: Attempt,
    cause: Throwable?,
  ) {
    withRecipeLoggingContext {
      val thread = attempt.owner
      // Only this attempt's hold: a stale event must not take a newer hold of the same thread
      val data = threadData[thread]?.takeIf { it.attempt === attempt } ?: return
      if (!threadData.remove(thread, data)) return
      dispossessed[thread] = data.holdCount
      logger.warn(cause) { "Lock on $lockPath lost by $clientId (lease expired)" }
      recordException(cause ?: EtcdRecipeRuntimeException("Lock lease for $lockPath expired; lock lost"))
      reportLeaseEvent(LeaseEvent.Expired(data.acquisitionLease.leaseId, cause))
      // Off jetcd's lease thread: a listener may block or make an RPC
      notifyAsync {
        lockLostListeners.forEach { listener ->
          try {
            listener.onLockLost(cause)
          } catch (e: Throwable) {
            logger.error(e) { "Exception in lock-lost listener" }
            recordException(e)
          }
        }
        if (interruptOnLockLoss) thread.interrupt()
      }
      data.acquisitionLease.closeWithoutRevoke() // lease already gone; no RPC on this thread
    }
  }

  private fun releaseHold(data: LockData) {
    // The hold is over: a fatal event for its lease from now on is stale
    data.attempt.phase.compareAndSet(Phase.HOLDING, Phase.DEAD)
    resilience.metrics.recordLockHold(lockPath, data.acquiredAt.elapsedNow())
    // Prompt FIFO handoff via the unlock RPC; the revoke below is belt-and-braces
    // (it deletes the ownership key even if the unlock RPC failed).
    runCatching { client.unlock(data.ownershipKey.asString, resilience.rpc) }
      .onFailure { e -> logger.debug(e) { "unlock RPC failed for $lockPath; revoke will release" } }
    data.acquisitionLease.close()
  }

  override fun doClose() {
    // Abort in-flight waits first, so an attempt about to win can't publish a hold after
    // the holds below are drained; each attempt's finally revokes its lease.
    attempts.toList().forEach { attempt ->
      if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
        attempt.future?.completeExceptionally(
          EtcdRecipeRuntimeException("Lock attempt on $lockPath aborted by close()"),
        )
      }
    }
    // Then release every thread's hold; owners become dispossessed so their later
    // unlock() returns false instead of throwing. Never blocks on user threads.
    threadData.keys.toList().forEach { thread ->
      threadData.remove(thread)?.let { data ->
        dispossessed[thread] = data.holdCount
        releaseHold(data)
      }
    }
    lockLostListeners.clear()
  }

  // An acquisition that close() landed on: it fails rather than acquire on a closed recipe
  private fun abortedByClose(cause: Throwable? = null): Nothing =
    throw EtcdRecipeRuntimeException("Lock attempt on $lockPath aborted by close()", cause)

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val LEASE_HEAL_PAUSE_MS = 250L
  }
}
