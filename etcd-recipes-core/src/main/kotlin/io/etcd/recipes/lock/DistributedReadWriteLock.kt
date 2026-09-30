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
import io.etcd.jetcd.KeyValue
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.isKeyPresent
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
import kotlin.time.Duration.Companion.milliseconds
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
 * (it would self-deadlock). A downgraded read keeps the write entry's place in line
 * (its *rank*, carried in the read entry's value), so a writer that queued behind the
 * write hold keeps waiting until the downgraded read is released too.
 */
class DistributedReadWriteLock
@JvmOverloads
constructor(
  client: Client,
  val lockPath: String,
  val leaseTtlSecs: Long = DEFAULT_LOCK_TTL_SECS,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  val clientId: String = defaultClientId(DistributedReadWriteLock::class.simpleName!!),
  internal val interruptOnLockLoss: Boolean = false,
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
    val rank: Long, // place in line: the entry's create revision, or an inherited one
    // The acquisition this hold came from: a loss is applied only to the hold it belongs to
    val attempt: Attempt,
    // The entry's own create revision: later than every conflicting hold granted before it
    val fencingToken: Long,
  ) {
    // Changed by the owner thread; read by a loss on jetcd's lease thread
    @Volatile
    var holdCount = 1
    val acquiredAt: ComparableTimeMark = TimeSource.Monotonic.markNow()
  }

  // One in-flight acquisition; the phase machine arbitrates fatal-vs-win exactly
  // like DistributedMutex's.
  private class Attempt(
    val owner: Thread,
    val entryKey: String,
  ) {
    val phase = AtomicReference(Phase.WAITING)
    val wake = AtomicReference<CountDownLatch?>(null)
  }

  private val readHolds = ConcurrentHashMap<Thread, EntryData>()
  private val writeHolds = ConcurrentHashMap<Thread, EntryData>()
  private val readDispossessed = ConcurrentHashMap<Thread, Int>()
  private val writeDispossessed = ConcurrentHashMap<Thread, Int>()
  private val readLostListeners = CopyOnWriteArrayList<LockLostListener>()
  private val writeLostListeners = CopyOnWriteArrayList<LockLostListener>()
  private val attempts = CopyOnWriteArrayList<Attempt>()

  // Entries live directly under this; the scan and the side classification both work
  // relative to it, so a sibling lock whose path merely shares the string prefix
  // (/order-1 vs /order-10) is never in the snapshot.
  private val entryParent = "$lockPath/"

  init {
    require(lockPath.isNotEmpty()) { "Lock path cannot be empty" }
    require(leaseTtlSecs > 0) { "Lease TTL must be > 0" }
    // An entry's value is its clientId, so one shaped like a carried rank would be read as one
    require(!clientId.startsWith(RANK_VALUE_PREFIX)) { "clientId cannot start with \"$RANK_VALUE_PREFIX\": $clientId" }
  }

  override val exceptionContext get() = "DistributedReadWriteLock[$lockPath]"

  val readLock: EtcdLock = LockView(Side.READ)
  val writeLock: EtcdLock = LockView(Side.WRITE)

  private fun holdsFor(side: Side) = if (side == Side.READ) readHolds else writeHolds

  private fun dispossessedFor(side: Side) = if (side == Side.READ) readDispossessed else writeDispossessed

  private fun listenersFor(side: Side) = if (side == Side.READ) readLostListeners else writeLostListeners

  internal inner class LockView(
    private val side: Side,
  ) : EtcdLock {
    // The owning lock's interruptOnLockLoss, for the suspend surface
    internal val interruptsOnLoss: Boolean get() = interruptOnLockLoss

    override fun lock() {
      val timed = !isHeldByCurrentThread // don't time reentrant re-locks
      val start = TimeSource.Monotonic.markNow()
      check(acquire(side, null)) { "unbounded acquisition returned without the lock" }
      if (timed) resilience.metrics.recordLockWait(lockPath, start.elapsedNow(), acquired = true)
    }

    override fun tryLock(timeout: Duration): Boolean {
      require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
      val timed = !isHeldByCurrentThread
      val start = TimeSource.Monotonic.markNow()
      val acquired = acquire(side, TimeSource.Monotonic.markNow() + timeout)
      if (timed) resilience.metrics.recordLockWait(lockPath, start.elapsedNow(), acquired)
      return acquired
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
        return client.getChildrenKeys(lockPath, rpc = resilience.rpc).any { isEntryOf(it, side) }
      }

    override val holdCount: Int get() = holdsFor(side)[Thread.currentThread()]?.holdCount ?: 0

    override val fencingToken: Long get() = holdsFor(side)[Thread.currentThread()]?.fencingToken ?: -1L

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
      // The hold is over: a fatal event for its lease from now on is stale
      data.attempt.phase.compareAndSet(Phase.HOLDING, Phase.DEAD)
      resilience.metrics.recordLockHold(lockPath, data.acquiredAt.elapsedNow())
      data.lease.close() // revoke (retried) deletes the entry, waking successors
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
    "ThrowsCount",
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

    var mayInheritWriteRank = true
    outer@ while (true) {
      checkCloseNotCalled()
      if (deadline.hasPassed()) return false
      // A deadline bounds every RPC of the attempt, not just the wait
      val bounded = resilience.within(deadline)

      val entryKey = "$entryParent${side.entryPrefix}$clientId:${randomId(TOKEN_LENGTH)}"
      // A write→read downgrade inherits the write entry's rank: see effectiveRank.
      val inheritedFrom = if (side == Side.READ && mayInheritWriteRank) writeHolds[me] else null
      val inheritedRank = inheritedFrom?.rank
      val attempt = Attempt(me, entryKey)
      val lease =
        try {
          AcquisitionLease(
            client,
            leaseTtlSecs,
            bounded.rpc,
            onTransient = { leaseId, e ->
              recordException(e)
              reportLeaseEvent(LeaseEvent.Suspended(leaseId, e))
            },
            onResumed = { leaseId -> reportLeaseEvent(LeaseEvent.Restored(leaseId, leaseId)) },
            onFatal = { cause -> onEntryFatal(side, attempt, cause) },
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
        val txn =
          client.transaction(bounded.rpc) {
            If(entryKey.doesNotExist)
            Then(
              entryKey.setTo(
                inheritedRank?.let { "$RANK_VALUE_PREFIX$it" } ?: clientId,
                putOption { withLeaseId(lease.leaseId) },
              ),
            )
          }
        if (!txn.isSucceeded) {
          // Random-suffix collision: effectively impossible; pace and retry
          pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
          continue@outer
        }
        val ownCreateRevision =
          client.getResponse(entryKey, rpc = bounded.rpc).kvs.firstOrNull()?.createRevision
            ?: run {
              // Entry already gone: the lease died in the creation window
              pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
              continue@outer
            }
        val ownRank = inheritedRank ?: ownCreateRevision

        while (true) {
          if (closeCalled.load()) abortedByClose()
          if (attempt.phase.load() == Phase.DEAD) {
            pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
            continue@outer // fresh entry at the tail
          }
          if (deadline.hasPassed()) return false

          val conflict = nearestConflict(side, me, entryKey, ownRank, bounded.rpc)
            ?: run {
              // A downgrade may only keep the write entry's place while that entry
              // still exists: once it is gone (a lease expiry not yet noticed here), a
              // writer queued behind it may already hold the lock. Retry as an
              // ordinary read at the tail. Checked after this entry was created, so a
              // write that vanishes later still finds this entry ahead of any writer.
              if (inheritedFrom != null && !client.isKeyPresent(inheritedFrom.entryKey, bounded.rpc)) {
                mayInheritWriteRank = false
                continue@outer
              }
              // Admitted: publish the hold BEFORE claiming the phase (a fatal in
              // the win window must always find the hold — or the CAS failure
              // below rolls it back).
              val data = EntryData(lease, entryKey, ownRank, attempt, ownCreateRevision)
              holdsFor(side)[me] = data
              if (attempt.phase.compareAndSet(Phase.WAITING, Phase.HOLDING)) {
                if (closeCalled.load()) {
                  // close() landed in the win window: don't hand out a lock on a closed lock
                  holdsFor(side).remove(me, data)
                  dispossessedFor(side).remove(me)
                  abortedByClose()
                }
                dispossessedFor(side).remove(me)
                acquired = true
                return true
              }
              holdsFor(side).remove(me, data)
              pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
              continue@outer
            }

          val latch = CountDownLatch(1)
          attempt.wake.store(latch)
          if (attempt.phase.load() == Phase.DEAD) latch.countDown() // fatal raced the install
          withRecipeLoggingContext {
            WaiterSupport.awaitKeyDeletion(
              client,
              conflict.key,
              bounded,
              latch,
              deadline,
              observedRevision = conflict.observedRevision,
              reportRecovery = { event -> reportRecoveryEvent(event) },
              recordException = { e -> recordException(e) },
            )
          }
          attempt.wake.store(null)
          // Loop: re-evaluate the conflict set (it only shrinks)
        }
      } catch (e: EtcdRecipeRuntimeException) {
        // An RPC that failed once the deadline passed (bounded by it, it timed out): time ran out
        if (deadline.hasPassed() && !closeCalled.load()) return false
        throw e
      } finally {
        attempts -= attempt
        if (!acquired) {
          // Revoke deletes the entry (safe on an already-dead lease), waking successors. A
          // bounded acquisition gives it one short attempt, so it returns near its deadline.
          if (deadline == null) lease.close() else lease.closePromptly()
        }
      }
      @Suppress("UNREACHABLE_CODE")
      error("unreachable")
    }
  }

  // One consistent snapshot (a single ranged read) of this lock's entries. The
  // nearest EARLIER-ranked conflicting entry is the wait target; the calling thread's
  // own write entry is excluded so write→read downgrade admits. The snapshot's revision
  // rides along so the wait can anchor its DELETE-watch at the point the conflict was
  // observed present (see WaiterSupport).
  private fun nearestConflict(
    side: Side,
    thread: Thread,
    ownEntryKey: String,
    ownRank: Long,
    rpc: RpcResilience,
  ): Conflict? {
    val snapshot = client.getResponse(entryParent, getOption { isPrefix(true) }, rpc)
    val ownWriteEntry = writeHolds[thread]?.entryKey
    val conflict =
      snapshot.kvs
        .asSequence()
        .map { kv -> kv.key.asString to effectiveRank(kv) }
        .filter { (key, rank) -> rank < ownRank && key != ownEntryKey && key != ownWriteEntry }
        // any earlier entry conflicts with a writer; only earlier writers with a reader
        .filter { (key, _) -> side == Side.WRITE || isEntryOf(key, Side.WRITE) }
        .maxByOrNull { (_, rank) -> rank }
        ?: return null
    return Conflict(conflict.first, snapshot.header.revision)
  }

  // An entry's side, judged by its name under the lock path. Not by the last path
  // segment: a clientId may itself contain '/'.
  private fun isEntryOf(
    key: String,
    side: Side,
  ): Boolean = key.removePrefix(entryParent).startsWith(side.entryPrefix)

  // An entry's place in line. Normally its create revision; a downgraded read entry
  // carries the rank of the write entry it was taken under, so it sorts where that write
  // did. That rank is always inherited while the write entry still exists, so a writer
  // queued behind the write sees the downgraded read before the write can vanish.
  private fun effectiveRank(kv: KeyValue): Long =
    kv.value.asString
      .takeIf { it.startsWith(RANK_VALUE_PREFIX) }
      ?.removePrefix(RANK_VALUE_PREFIX)
      ?.toLongOrNull()
      ?: kv.createRevision

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
      recordException(
        cause ?: EtcdRecipeRuntimeException("Lock entry lease expired while waiting on $lockPath"),
      )
      attempt.wake.load()?.countDown()
    } else if (attempt.phase.load() == Phase.HOLDING) {
      lockLost(side, attempt, cause)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun lockLost(
    side: Side,
    attempt: Attempt,
    cause: Throwable?,
  ) {
    withRecipeLoggingContext {
      val thread = attempt.owner
      // Only this attempt's hold: a stale event must not take a newer hold of the same thread
      val data = holdsFor(side)[thread]?.takeIf { it.attempt === attempt } ?: return
      if (!holdsFor(side).remove(thread, data)) return
      dispossessedFor(side)[thread] = data.holdCount
      logger.warn(cause) { "${side.entryPrefix} lock on $lockPath lost by $clientId (lease expired)" }
      recordException(cause ?: EtcdRecipeRuntimeException("Lock lease for $lockPath expired; lock lost"))
      reportLeaseEvent(LeaseEvent.Expired(data.lease.leaseId, cause))
      // Off jetcd's lease thread: a listener may block or make an RPC
      notifyAsync {
        listenersFor(side).forEach { listener ->
          try {
            listener.onLockLost(cause)
          } catch (e: Throwable) {
            logger.error(e) { "Exception in lock-lost listener" }
            recordException(e)
          }
        }
        if (interruptOnLockLoss) thread.interrupt()
      }
      data.lease.closeWithoutRevoke() // lease already gone; no RPC on this thread
    }
  }

  override fun doClose() {
    // Abort in-flight waits first, so an attempt about to win can't publish a hold after
    // the holds below are drained.
    attempts.toList().forEach { attempt ->
      if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
        attempt.wake.load()?.countDown()
      }
    }
    [Side.READ, Side.WRITE].forEach { side ->
      val holds = holdsFor(side)
      holds.keys.toList().forEach { thread ->
        holds.remove(thread)?.let { data ->
          dispossessedFor(side)[thread] = data.holdCount
          data.lease.close()
        }
      }
    }
    readLostListeners.clear()
    writeLostListeners.clear()
  }

  // An acquisition that close() landed on: it fails rather than acquire on a closed recipe
  private fun abortedByClose(cause: Throwable? = null): Nothing =
    throw EtcdRecipeRuntimeException("Lock attempt on $lockPath aborted by close()", cause)

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val LEASE_HEAL_PAUSE_MS = 250L

    // Value prefix of a downgraded read entry: "rank:<revision>". Other entries hold the clientId.
    private const val RANK_VALUE_PREFIX = "rank:"
  }
}
