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
    val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    val clientId: String = defaultClientId(DistributedMutex::class.simpleName!!),
    private val interruptOnLockLoss: Boolean = false,
  ) : EtcdConnector(client, resilience),
    EtcdLock {
  private class LockData(
    val acquisitionLease: AcquisitionLease,
    val ownershipKey: ByteSequence,
  ) {
    var holdCount = 1 // guarded by owner-thread confinement
  }

  private enum class Phase { WAITING, HOLDING, DEAD }

  // One in-flight acquisition: the phase machine resolves the race between the
  // keep-alive's fatal callback and the acquirer's win. Exactly one of
  // {retry-as-loser, lockLost} runs.
  private inner class Attempt(
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

  override fun lock() {
    check(acquire(null)) { "unbounded acquisition returned without the lock" }
  }

  override fun tryLock(timeout: Duration): Boolean {
    require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
    return acquire(TimeSource.Monotonic.markNow() + timeout)
  }

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

  @Suppress("ReturnCount", "ThrowsCount", "LoopWithTooManyJumpStatements", "TooGenericExceptionCaught", "LongMethod")
  private fun acquire(deadline: ComparableTimeMark?): Boolean {
    checkCloseNotCalled()
    val me = Thread.currentThread()
    threadData[me]?.let { data ->
      data.holdCount += 1
      return true
    }

    while (true) {
      checkCloseNotCalled()
      if (deadline != null && deadline.hasPassedNow()) return false

      val attempt = Attempt(me)
      // The lease is kept alive from grant, through the server-side wait, and
      // across the hold; its fatal callback drives both mid-wait aborts and
      // lock-lost while holding.
      val lease =
        AcquisitionLease(
          client,
          leaseTtlSecs,
          resilience.rpc,
          onTransient = { e ->
            exceptionList.value += e
            reportLeaseEvent(LeaseEvent.Suspended(-1L, e))
          },
          onFatal = { cause -> onAttemptFatal(attempt, cause) },
        )
      attempts += attempt
      var acquired = false
      try {
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
            // Lease death mid-wait, "no leader", or a close() abort. Retry with a
            // fresh lease (paced); tryLock stays bounded by the loop's deadline check.
            exceptionList.value += e
            if (closeCalled.load()) {
              throw EtcdRecipeRuntimeException("Lock attempt on $lockPath aborted by close()", e)
            }
            Thread.sleep(LEASE_HEAL_PAUSE_MS)
            continue
          }

        // Publish the hold BEFORE claiming the phase, so a fatal that lands in the
        // win window always finds the hold to dispossess (or the CAS failure below
        // rolls it back) — never a silently-dead "held" lock.
        threadData[me] = LockData(lease, response.key)
        if (attempt.phase.compareAndSet(Phase.WAITING, Phase.HOLDING)) {
          dispossessed.remove(me)
          acquired = true
          return true
        }
        // The lease died in the win window: roll back and retry as a loser
        threadData.remove(me)
        continue
      } finally {
        attempts -= attempt
        if (!acquired) {
          // Revoke is idempotent and safe on an already-dead lease; it deletes any
          // just-granted ownership key and aborts the server-side wait.
          lease.close()
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
      lockLost(attempt.owner, cause)
    }
  }

  // Cooperative dispossession (once-guarded by the map removal): state flips,
  // listeners fire, LOST is reported; interruption is opt-in because critical
  // sections are inline user code.
  @Suppress("TooGenericExceptionCaught")
  private fun lockLost(
    thread: Thread,
    cause: Throwable?,
  ) {
    val data = threadData.remove(thread) ?: return
    dispossessed[thread] = data.holdCount
    logger.warn(cause) { "Lock on $lockPath lost by $clientId (lease expired)" }
    exceptionList.value += (cause ?: EtcdRecipeRuntimeException("Lock lease for $lockPath expired; lock lost"))
    reportLeaseEvent(LeaseEvent.Expired(-1L, cause))
    lockLostListeners.forEach { listener ->
      try {
        listener.onLockLost(cause)
      } catch (e: Throwable) {
        logger.error(e) { "Exception in lock-lost listener" }
        exceptionList.value += e
      }
    }
    if (interruptOnLockLoss) thread.interrupt()
    data.acquisitionLease.closeWithoutRevoke() // lease already gone; no RPC on this thread
  }

  private fun releaseHold(data: LockData) {
    // Prompt FIFO handoff via the unlock RPC; the revoke below is belt-and-braces
    // (it deletes the ownership key even if the unlock RPC failed).
    runCatching { client.unlock(data.ownershipKey.asString, resilience.rpc) }
      .onFailure { e -> logger.debug(e) { "unlock RPC failed for $lockPath; revoke will release" } }
    data.acquisitionLease.close()
  }

  override fun doClose() {
    // Release every thread's hold; owners become dispossessed so their later
    // unlock() returns false instead of throwing. Never blocks on user threads.
    threadData.keys.toList().forEach { thread ->
      threadData.remove(thread)?.let { data ->
        dispossessed[thread] = data.holdCount
        releaseHold(data)
      }
    }
    // Abort in-flight waits; each attempt's finally revokes its lease.
    attempts.toList().forEach { attempt ->
      if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
        attempt.future?.completeExceptionally(
          EtcdRecipeRuntimeException("Lock attempt on $lockPath aborted by close()"),
        )
      }
    }
    lockLostListeners.clear()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val LEASE_HEAL_PAUSE_MS = 250L
  }
}
