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
import com.pambrose.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.jetcd.options.GetOption
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A fair (FIFO by create revision) distributed read-write lock. Readers share;
 * writers exclude everyone; arrivals are honored in revision order, so a queued
 * writer cannot be starved by later readers — Curator-parity semantics (the
 * "writer-priority" of the product doc).
 *
 * Hand-rolled key scheme (etcd's native lock service cannot express shared
 * holds): each acquisition creates a lease-bound `read-`/`write-` entry under the
 * lock path and waits on the DELETE of its *nearest conflicting predecessor* —
 * herd-free, and correct because the conflict predicate is set-emptiness over
 * earlier entries: the watched key is always in the set, so the set cannot empty
 * without a wakeup, and new arrivals always rank later.
 *
 * Thread-per-acquisition holds, per-side reentrancy, cooperative lock-lost, and
 * write→read downgrade are as in [DistributedMutex]; read→write upgrade throws
 * (it would self-deadlock).
 */
class DistributedReadWriteLock
  @JvmOverloads
  constructor(
    client: Client,
    val lockPath: String,
    val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    val clientId: String = defaultClientId(DistributedReadWriteLock::class.simpleName!!),
    private val interruptOnLockLoss: Boolean = false,
  ) : EtcdConnector(client, resilience) {
  internal enum class Side(
    val entryPrefix: String,
  ) {
    READ("read-"),
    WRITE("write-"),
  }

  private enum class Phase { WAITING, HOLDING, DEAD }

  private class EntryData(
    val lease: AcquisitionLease,
    val entryKey: String,
  ) {
    var holdCount = 1 // owner-thread confined
  }

  // One in-flight acquisition; the phase machine arbitrates fatal-vs-win exactly
  // like DistributedMutex's.
  private class Attempt(
    val owner: Thread,
    val entryKey: String,
  ) {
    val phase = AtomicReference(Phase.WAITING)
    val wake = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)
  }

  private val readHolds = ConcurrentHashMap<Thread, EntryData>()
  private val writeHolds = ConcurrentHashMap<Thread, EntryData>()
  private val readDispossessed = ConcurrentHashMap<Thread, Int>()
  private val writeDispossessed = ConcurrentHashMap<Thread, Int>()
  private val readLostListeners = CopyOnWriteArrayList<LockLostListener>()
  private val writeLostListeners = CopyOnWriteArrayList<LockLostListener>()
  private val attempts = CopyOnWriteArrayList<Attempt>()

  init {
    require(lockPath.isNotEmpty()) { "Lock path cannot be empty" }
    require(leaseTtlSecs > 0) { "Lease TTL must be > 0" }
  }

  val readLock: EtcdLock = LockView(Side.READ)
  val writeLock: EtcdLock = LockView(Side.WRITE)

  private fun holdsFor(side: Side) = if (side == Side.READ) readHolds else writeHolds

  private fun dispossessedFor(side: Side) = if (side == Side.READ) readDispossessed else writeDispossessed

  private fun listenersFor(side: Side) = if (side == Side.READ) readLostListeners else writeLostListeners

  private inner class LockView(
    private val side: Side,
  ) : EtcdLock {
    override fun lock() {
      check(acquire(side, null)) { "unbounded acquisition returned without the lock" }
    }

    override fun tryLock(timeout: Duration): Boolean {
      require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
      return acquire(side, TimeSource.Monotonic.markNow() + timeout)
    }

    override fun tryLock(
      timeout: Long,
      timeUnit: TimeUnit,
    ): Boolean = tryLock(timeUnitToDuration(timeout, timeUnit))

    override fun unlock(): Boolean = release(side)

    override val isHeldByCurrentThread: Boolean get() = holdsFor(side).containsKey(Thread.currentThread())

    override val isLocked: Boolean
      get() {
        checkCloseNotCalled()
        return client.getChildrenKeys(lockPath, rpc = resilience.rpc)
          .any { it.substringAfterLast('/').startsWith(side.entryPrefix) }
      }

    override val holdCount: Int get() = holdsFor(side)[Thread.currentThread()]?.holdCount ?: 0

    override fun addLockLostListener(listener: LockLostListener) {
      listenersFor(side) += listener
    }

    override fun removeLockLostListener(listener: LockLostListener) {
      listenersFor(side) -= listener
    }
  }

  @Suppress("ReturnCount")
  private fun release(side: Side): Boolean {
    val me = Thread.currentThread()
    val holds = holdsFor(side)
    val data = holds[me]
    if (data != null) {
      if (data.holdCount > 1) {
        data.holdCount -= 1
        return true
      }
      holds.remove(me)
      data.lease.close() // revoke deletes the entry, waking successors
      return true
    }

    val disposs = dispossessedFor(side)
    val lostHolds = disposs[me]
    if (lostHolds != null) {
      if (lostHolds > 1) disposs[me] = lostHolds - 1 else disposs.remove(me)
      logger.debug { "unlock() on $lockPath (${side.entryPrefix}) after the hold was lost or released by close()" }
      return false
    }

    throw IllegalMonitorStateException("Current thread does not hold the ${side.entryPrefix} lock on $lockPath")
  }

  @Suppress(
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
  )
  private fun acquire(
    side: Side,
    deadline: ComparableTimeMark?,
  ): Boolean {
    checkCloseNotCalled()
    val me = Thread.currentThread()
    holdsFor(side)[me]?.let { data ->
      data.holdCount += 1
      return true
    }
    if (side == Side.WRITE && readHolds.containsKey(me)) {
      throw EtcdRecipeRuntimeException(
        "Read-to-write upgrade is not supported on $lockPath (it would self-deadlock)",
      )
    }

    outer@ while (true) {
      checkCloseNotCalled()
      if (deadline != null && deadline.hasPassedNow()) return false

      val entryKey = "$lockPath/${side.entryPrefix}$clientId:${randomId(TOKEN_LENGTH)}"
      val attempt = Attempt(me, entryKey)
      val lease =
        AcquisitionLease(
          client,
          leaseTtlSecs,
          resilience.rpc,
          onTransient = { e ->
            exceptionList.value += e
            reportLeaseEvent(LeaseEvent.Suspended(-1L, e))
          },
          onFatal = { cause -> onEntryFatal(side, attempt, cause) },
        )
      attempts += attempt
      var acquired = false
      try {
        val txn =
          client.transaction(resilience.rpc) {
            If(entryKey.doesNotExist)
            Then(entryKey.setTo(clientId, putOption { withLeaseId(lease.leaseId) }))
          }
        if (!txn.isSucceeded) {
          // Random-suffix collision: effectively impossible; pace and retry
          Thread.sleep(LEASE_HEAL_PAUSE_MS)
          continue@outer
        }
        val ownCreateRevision =
          client.getResponse(entryKey, rpc = resilience.rpc).kvs.firstOrNull()?.createRevision
            ?: run {
              // Entry already gone: the lease died in the creation window
              Thread.sleep(LEASE_HEAL_PAUSE_MS)
              continue@outer
            }

        while (true) {
          if (attempt.phase.load() == Phase.DEAD) {
            Thread.sleep(LEASE_HEAL_PAUSE_MS)
            continue@outer // fresh entry at the tail
          }
          if (deadline != null && deadline.hasPassedNow()) return false

          val conflict = nearestConflict(side, me, entryKey, ownCreateRevision)
            ?: run {
              // Admitted: publish the hold BEFORE claiming the phase (a fatal in
              // the win window must always find the hold — or the CAS failure
              // below rolls it back).
              holdsFor(side)[me] = EntryData(lease, entryKey)
              if (attempt.phase.compareAndSet(Phase.WAITING, Phase.HOLDING)) {
                dispossessedFor(side).remove(me)
                acquired = true
                return true
              }
              holdsFor(side).remove(me)
              Thread.sleep(LEASE_HEAL_PAUSE_MS)
              continue@outer
            }

          val latch = CountDownLatch(1)
          attempt.wake.set(latch)
          if (attempt.phase.load() == Phase.DEAD) latch.countDown() // fatal raced the install
          WaiterSupport.awaitKeyDeletion(
            client,
            conflict.key,
            resilience,
            latch,
            deadline,
            observedRevision = conflict.observedRevision,
            reportRecovery = { event -> reportRecoveryEvent(event) },
            recordException = { e -> exceptionList.value += e },
          )
          attempt.wake.set(null)
          // Loop: re-evaluate the conflict set (it only shrinks)
        }
      } finally {
        attempts -= attempt
        if (!acquired) {
          // Revoke deletes the entry (safe on an already-dead lease), waking successors
          lease.close()
        }
      }
      @Suppress("UNREACHABLE_CODE")
      error("unreachable")
    }
  }

  // One consistent snapshot (a single ranged read), sorted by create revision.
  // The nearest EARLIER conflicting entry is the wait target; the calling
  // thread's own write entry is excluded so write→read downgrade admits. The
  // snapshot's revision rides along so the wait can anchor its DELETE-watch at
  // the point the conflict was observed present (see WaiterSupport).
  private fun nearestConflict(
    side: Side,
    thread: Thread,
    ownEntryKey: String,
    ownCreateRevision: Long,
  ): Conflict? {
    val snapshot =
      client.getResponse(
        lockPath,
        getOption {
          isPrefix(true)
          withSortField(GetOption.SortTarget.CREATE)
          withSortOrder(GetOption.SortOrder.ASCEND)
        },
        resilience.rpc,
      )
    val ownWriteEntry = writeHolds[thread]?.entryKey
    val conflict =
      snapshot.kvs
        .asSequence()
        .filter { it.createRevision < ownCreateRevision }
        .filter { kv ->
          val basename = kv.key.asString.substringAfterLast('/')
          when (side) {
            Side.WRITE -> true

            // any earlier entry conflicts with a writer
            Side.READ -> basename.startsWith(Side.WRITE.entryPrefix)
          }
        }
        .filter { it.key.asString != ownEntryKey && it.key.asString != ownWriteEntry }
        .maxByOrNull { it.createRevision }
        ?: return null
    return Conflict(conflict.key.asString, snapshot.header.revision)
  }

  // Nearest earlier conflicting entry plus the revision at which the ranged read
  // observed it present.
  private data class Conflict(
    val key: String,
    val observedRevision: Long,
  )

  // Runs on jetcd's lease callback thread — no blocking RPCs here. The phase
  // machine guarantees exactly one of {waiter-abort, lockLost} runs.
  private fun onEntryFatal(
    side: Side,
    attempt: Attempt,
    cause: Throwable?,
  ) {
    if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
      exceptionList.value += (
        cause ?: EtcdRecipeRuntimeException("Lock entry lease expired while waiting on $lockPath")
      )
      attempt.wake.get()?.countDown()
    } else if (attempt.phase.load() == Phase.HOLDING) {
      lockLost(side, attempt.owner, cause)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun lockLost(
    side: Side,
    thread: Thread,
    cause: Throwable?,
  ) {
    val data = holdsFor(side).remove(thread) ?: return
    dispossessedFor(side)[thread] = data.holdCount
    logger.warn(cause) { "${side.entryPrefix} lock on $lockPath lost by $clientId (lease expired)" }
    exceptionList.value += (cause ?: EtcdRecipeRuntimeException("Lock lease for $lockPath expired; lock lost"))
    reportLeaseEvent(LeaseEvent.Expired(-1L, cause))
    listenersFor(side).forEach { listener ->
      try {
        listener.onLockLost(cause)
      } catch (e: Throwable) {
        logger.error(e) { "Exception in lock-lost listener" }
        exceptionList.value += e
      }
    }
    if (interruptOnLockLoss) thread.interrupt()
    data.lease.closeWithoutRevoke() // lease already gone; no RPC on this thread
  }

  override fun doClose() {
    listOf(Side.READ, Side.WRITE).forEach { side ->
      val holds = holdsFor(side)
      holds.keys.toList().forEach { thread ->
        holds.remove(thread)?.let { data ->
          dispossessedFor(side)[thread] = data.holdCount
          data.lease.close()
        }
      }
    }
    attempts.toList().forEach { attempt ->
      if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
        attempt.wake.get()?.countDown()
      }
    }
    readLostListeners.clear()
    writeLostListeners.clear()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val LEASE_HEAL_PAUSE_MS = 250L
  }
}
