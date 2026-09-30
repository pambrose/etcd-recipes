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
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Notified when a held permit's lease expires (crash/partition) and the permit is lost. Runs
 * on the semaphore's notifier thread, never on jetcd's lease callback thread, so it may block
 * or make RPCs; it delays only the semaphore's later notifications.
 */
fun interface PermitLostListener {
  fun onPermitLost(cause: Throwable?)
}

/**
 * Thrown on first use when the canonical permit count stored at the semaphore
 * path disagrees with the count this instance was constructed with.
 */
class SemaphorePermitMismatchException(
  val semaphorePath: String,
  val requestedPermits: Int,
  val canonicalPermits: Int,
) : EtcdRecipeRuntimeException(
    "Semaphore at $semaphorePath already exists with $canonicalPermits permits; " +
      "this instance requested $requestedPermits",
  )

/**
 * A distributed counting semaphore. The canonical permit count is CAS-created at
 * `<semaphorePath>/permits` on first use and validated by every later instance
 * (mismatch throws [SemaphorePermitMismatchException]). Each acquisition places a
 * lease-bound entry under `<semaphorePath>/holders/`; an entry holds a permit iff
 * its create-revision rank is below the permit count — rank only shrinks while an
 * entry lives, so capacity is provably never exceeded and grants are FIFO.
 *
 * Unlike [EtcdLock], holds are instance-level, Java-`Semaphore`-style: any thread
 * may [release], and acquisitions are never reentrant (each [acquire] consumes a
 * fresh permit). A release gives up a permit the calling thread acquired first (see
 * [release]). A permit whose lease expires is lost **cooperatively**: capacity frees
 * server-side, listeners fire, connection state reports LOST, and the acquiring
 * thread's matching [release] returns false (interruption of the acquiring thread is
 * opt-in via `interruptOnPermitLoss`).
 *
 * Waiters watch the holders prefix for any DELETE and re-evaluate their rank —
 * rank admission has no single predecessor key, so a prefix watch is the only
 * sound wakeup (a nearest-predecessor watch can miss multi-departure windows).
 */
class DistributedSemaphore
  @JvmOverloads
  constructor(
    client: Client,
    val semaphorePath: String,
    val permits: Int,
    val leaseTtlSecs: Long = DEFAULT_LOCK_TTL_SECS,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    val clientId: String = defaultClientId(DistributedSemaphore::class.simpleName!!),
    internal val interruptOnPermitLoss: Boolean = false,
  ) : EtcdConnector(client, resilience) {
  private enum class Phase { WAITING, HOLDING, DEAD }

  private class PermitData(
    val lease: AcquisitionLease,
    val entryKey: String,
    val owner: Thread,
    // The entry's create revision: permits are granted in its order
    val fencingToken: Long,
  ) {
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

    @Volatile
    var holdData: PermitData? = null
  }

  private val holds = ConcurrentLinkedDeque<PermitData>()

  // Permits lost (lease expired, or released by close()) that a release() hasn't consumed
  // yet, per acquiring thread, so a release consumes its own lost permit rather than giving
  // up another thread's live one.
  private val lostPermits = ConcurrentHashMap<Thread, Int>()
  private val lostListeners = CopyOnWriteArrayList<PermitLostListener>()
  private val attempts = CopyOnWriteArrayList<Attempt>()
  private val permitsValidated = AtomicBoolean(false)

  private val permitsKey = "$semaphorePath/permits"
  private val holdersPath = "$semaphorePath/holders"

  init {
    require(semaphorePath.isNotEmpty()) { "Semaphore path cannot be empty" }
    require(permits >= 1) { "Permits must be >= 1: $permits" }
    require(leaseTtlSecs > 0) { "Lease TTL must be > 0" }
  }

  override val exceptionContext get() = "DistributedSemaphore[$semaphorePath]"

  @Throws(InterruptedException::class)
  fun acquire() {
    val start = TimeSource.Monotonic.markNow()
    check(acquireInternal(null)) { "unbounded acquisition returned without a permit" }
    resilience.metrics.recordLockWait(semaphorePath, start.elapsedNow(), acquired = true)
  }

  @Throws(InterruptedException::class)
  fun tryAcquire(timeout: Duration): Boolean {
    require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
    val start = TimeSource.Monotonic.markNow()
    val acquired = acquireInternal(TimeSource.Monotonic.markNow() + timeout)
    resilience.metrics.recordLockWait(semaphorePath, start.elapsedNow(), acquired)
    return acquired
  }

  @Throws(InterruptedException::class)
  fun tryAcquire(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = tryAcquire(timeUnitToDuration(timeout, timeUnit))

  /**
   * Releases a permit, choosing in this order: a live permit the calling thread acquired;
   * else one it acquired that was lost (lease expired, or released by close()), and then
   * returns false; else, for an acquire and release on different threads, any live permit,
   * then any lost one. So a thread whose permit was lost never gives up another thread's
   * live permit. Throws [IllegalStateException] when this instance holds nothing at all.
   */
  fun release(): Boolean = release(Thread.currentThread())

  // [release] on behalf of [owner], the thread that acquired the permit (withPermit acquires
  // on one thread and releases on another).
  @Suppress("ReturnCount")
  internal fun release(owner: Thread): Boolean {
    holds.firstOrNull { it.owner === owner }?.let { data ->
      if (holds.remove(data)) return releaseHold(data)
    }
    if (takeLostPermit(owner)) return false
    holds.pollFirst()?.let { return releaseHold(it) }
    if (lostPermits.keys.any { takeLostPermit(it) }) return false
    throw IllegalStateException("No permit is held on $semaphorePath by this instance")
  }

  private fun releaseHold(data: PermitData): Boolean {
    resilience.metrics.recordLockHold(semaphorePath, data.acquiredAt.elapsedNow())
    data.lease.close() // revoke (retried) deletes the entry, waking waiters
    return true
  }

  private fun takeLostPermit(owner: Thread): Boolean {
    var taken = false
    lostPermits.computeIfPresent(owner) { _, count ->
      taken = true
      if (count > 1) count - 1 else null
    }
    if (taken) logger.debug { "release() on $semaphorePath after the permit was lost or released by close()" }
    return taken
  }

  private fun recordLostPermit(owner: Thread) {
    lostPermits.merge(owner, 1, Int::plus)
  }

  /** Whether a live permit acquired on [owner] is still held (not released or lost). */
  internal fun holdsPermitAcquiredOn(owner: Thread): Boolean = holds.any { it.owner === owner }

  /**
   * The fencing token of the permit a [release] from the calling thread would give up, or -1
   * when it holds none: its entry's create revision, which grows with each grant. See
   * [EtcdLock.fencingToken].
   */
  val fencingToken: Long get() = holds.firstOrNull { it.owner === Thread.currentThread() }?.fencingToken ?: -1L

  /** Advisory: permits minus live holder/waiter entries, floored at zero. */
  fun availablePermits(): Int = availablePermits(resilience.rpc)

  /** [availablePermits] under [rpc], such as the single short attempt of [RpcResilience.PROBE] for a gauge. */
  fun availablePermits(rpc: RpcResilience): Int {
    checkCloseNotCalled()
    validatePermits(rpc)
    val entries = client.getChildCount(holdersPath, rpc).toInt()
    return (permits - entries).coerceAtLeast(0)
  }

  fun addPermitLostListener(listener: PermitLostListener) {
    lostListeners += listener
  }

  fun removePermitLostListener(listener: PermitLostListener) {
    lostListeners -= listener
  }

  // CAS-create the canonical count, or verify it matches; once per instance.
  // The ctor stays RPC-free like every recipe, so this runs lazily on first use.
  private fun validatePermits(rpc: RpcResilience = resilience.rpc) {
    if (permitsValidated.load()) return
    var canonical = -1
    while (canonical == -1) {
      // -1 = key deleted between the failed CAS and the read; CAS again
      val txn =
        client.transaction(rpc) {
          If(permitsKey.doesNotExist)
          Then(permitsKey setTo permits)
        }
      canonical = if (txn.isSucceeded) permits else client.getValue(permitsKey, -1, rpc)
    }
    if (canonical != permits) {
      throw SemaphorePermitMismatchException(semaphorePath, permits, canonical)
    }
    permitsValidated.store(true)
  }

  @Suppress(
    "ReturnCount",
    "ThrowsCount",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
  )
  private fun acquireInternal(deadline: ComparableTimeMark?): Boolean {
    checkCloseNotCalled()
    validatePermits()
    val me = Thread.currentThread()

    outer@ while (true) {
      checkCloseNotCalled()
      if (deadline.hasPassed()) return false
      // A deadline bounds every RPC of the attempt, not just the wait
      val bounded = resilience.within(deadline)

      val entryKey = "$holdersPath/$clientId:${randomId(TOKEN_LENGTH)}"
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
            onFatal = { cause -> onEntryFatal(attempt, cause) },
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
            Then(entryKey.setTo(clientId, putOption { withLeaseId(lease.leaseId) }))
          }
        if (!txn.isSucceeded) {
          // Random-suffix collision: effectively impossible; pace and retry
          pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
          continue@outer
        }

        while (true) {
          if (closeCalled.load())
            throw EtcdRecipeRuntimeException(
              "Permit attempt on $semaphorePath aborted by close()",
            )
          if (attempt.phase.load() == Phase.DEAD) {
            pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
            continue@outer // fresh entry at the tail
          }
          if (deadline.hasPassed()) return false

          val snap = rankOf(entryKey, bounded.rpc)
            ?: run {
              // Own entry gone: the lease died in the window
              pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
              continue@outer
            }
          if (snap.rank < permits) {
            // Admitted: publish the hold BEFORE claiming the phase (a fatal in
            // the win window must always find the hold — or the CAS failure
            // below rolls it back).
            val data = PermitData(lease, entryKey, me, txn.header.revision)
            attempt.holdData = data
            holds.addFirst(data)
            if (attempt.phase.compareAndSet(Phase.WAITING, Phase.HOLDING)) {
              if (closeCalled.load()) {
                // close() landed in the admission window: don't hand out a permit on a closed
                // semaphore. If close() already drained this hold, undo the lost permit it recorded.
                if (!holds.remove(data))
                  lostPermits.computeIfPresent(me) { _, count ->
                    if (count >
                  1
                    )
                    count - 1
                    else
                    null
                  }
                abortedByClose()
              }
              acquired = true
              return true
            }
            holds.remove(data)
            pauseWithin(LEASE_HEAL_PAUSE_MS.milliseconds, deadline)
            continue@outer
          }

          val latch = CountDownLatch(1)
          attempt.wake.store(latch)
          if (attempt.phase.load() == Phase.DEAD) latch.countDown() // fatal raced the install
          withRecipeLoggingContext {
            WaiterSupport.awaitPrefixDeletion(
              client,
              "$holdersPath/",
              bounded,
              latch,
              deadline,
              observedRevision = snap.observedRevision,
              shouldWake = {
                val now = rankOf(entryKey, bounded.rpc)
                now == null || now.rank < permits
              },
              reportRecovery = { event -> reportRecoveryEvent(event) },
              recordException = { e -> recordException(e) },
            )
          }
          attempt.wake.store(null)
          // Loop: re-evaluate the rank (it only shrinks while the entry lives)
        }
      } catch (e: EtcdRecipeRuntimeException) {
        // An RPC that failed once the deadline passed (bounded by it, it timed out): time ran out
        if (deadline.hasPassed() && !closeCalled.load()) return false
        throw e
      } finally {
        attempts -= attempt
        if (!acquired) {
          // Revoke deletes the entry (safe on an already-dead lease), waking waiters. A
          // bounded acquisition gives it one short attempt, so it returns near its deadline.
          if (deadline == null) lease.close() else lease.closePromptly()
        }
      }
      @Suppress("UNREACHABLE_CODE")
      error("unreachable")
    }
  }

  // Create-revision rank of the entry within one consistent prefix snapshot,
  // plus the revision at which that snapshot observed the blockers present (so
  // the wait can anchor its DELETE-watch there); null once the entry no longer
  // exists (its lease died).
  private fun rankOf(
    entryKey: String,
    rpc: RpcResilience,
  ): RankSnapshot? {
    val snapshot =
      client.getResponse(
        "$holdersPath/",
        getOption {
          isPrefix(true)
          withSortField(GetOption.SortTarget.CREATE)
          withSortOrder(GetOption.SortOrder.ASCEND)
        },
        rpc,
      )
    val idx = snapshot.kvs.indexOfFirst { it.key.asString == entryKey }
    return if (idx < 0) null else RankSnapshot(idx, snapshot.header.revision)
  }

  private data class RankSnapshot(
    val rank: Int,
    val observedRevision: Long,
  )

  // Runs on jetcd's lease callback thread — no blocking RPCs here. The phase
  // machine guarantees exactly one of {waiter-abort, permitLost} runs.
  private fun onEntryFatal(
    attempt: Attempt,
    cause: Throwable?,
  ) {
    if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
      recordException(
        cause ?: EtcdRecipeRuntimeException("Semaphore entry lease expired while waiting on $semaphorePath"),
      )
      attempt.wake.load()?.countDown()
    } else if (attempt.phase.load() == Phase.HOLDING) {
      permitLost(attempt, cause)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun permitLost(
    attempt: Attempt,
    cause: Throwable?,
  ) {
    withRecipeLoggingContext {
      val data = attempt.holdData ?: return
      if (!holds.remove(data)) return // already released, or close() took it
      recordLostPermit(data.owner)
      logger.warn(cause) { "Permit on $semaphorePath lost by $clientId (lease expired)" }
      recordException(cause ?: EtcdRecipeRuntimeException("Permit lease for $semaphorePath expired; permit lost"))
      reportLeaseEvent(LeaseEvent.Expired(data.lease.leaseId, cause))
      // Off jetcd's lease thread: a listener may block or make an RPC
      notifyAsync {
        lostListeners.forEach { listener ->
          try {
            listener.onPermitLost(cause)
          } catch (e: Throwable) {
            logger.error(e) { "Exception in permit-lost listener" }
            recordException(e)
          }
        }
        if (interruptOnPermitLoss) attempt.owner.interrupt()
      }
      data.lease.closeWithoutRevoke() // lease already gone; no RPC on this thread
    }
  }

  override fun doClose() {
    // Abort in-flight waits first, so an attempt about to be admitted can't publish a hold
    // after the holds below are drained.
    attempts.toList().forEach { attempt ->
      if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
        attempt.wake.load()?.countDown()
      }
    }
    while (true) {
      val data = holds.pollFirst() ?: break
      recordLostPermit(data.owner)
      data.lease.close()
    }
    lostListeners.clear()
  }

  // An acquisition that close() landed on: it fails rather than acquire on a closed recipe
  private fun abortedByClose(cause: Throwable? = null): Nothing =
    throw EtcdRecipeRuntimeException("Permit attempt on $semaphorePath aborted by close()", cause)

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val LEASE_HEAL_PAUSE_MS = 250L
  }
}

/** Runs [block] while holding a permit, releasing it on every exit path. */
inline fun <T> DistributedSemaphore.withPermit(block: () -> T): T {
  acquire()
  try {
    return block()
  } finally {
    release()
  }
}
