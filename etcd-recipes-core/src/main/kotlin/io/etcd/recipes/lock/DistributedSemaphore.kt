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
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlin.time.TimeSource

/** Notified when a held permit's lease expires (crash/partition) and the permit is lost. */
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
 * may [release], releases are LIFO among this instance's holds, and acquisitions
 * are never reentrant (each [acquire] consumes a fresh permit). A permit whose
 * lease expires is lost **cooperatively**: capacity frees server-side, listeners
 * fire, connection state reports LOST, and the matching [release] returns false
 * (interruption of the acquiring thread is opt-in via `interruptOnPermitLoss`).
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
    val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    val clientId: String = defaultClientId(DistributedSemaphore::class.simpleName!!),
    private val interruptOnPermitLoss: Boolean = false,
  ) : EtcdConnector(client, resilience) {
  private enum class Phase { WAITING, HOLDING, DEAD }

  private class PermitData(
    val lease: AcquisitionLease,
    val entryKey: String,
    val owner: Thread,
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
  private val dispossessedCount = AtomicInt(0)
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

  fun acquire() {
    val start = TimeSource.Monotonic.markNow()
    check(acquireInternal(null)) { "unbounded acquisition returned without a permit" }
    resilience.metrics.recordLockWait(semaphorePath, start.elapsedNow(), acquired = true)
  }

  fun tryAcquire(timeout: Duration): Boolean {
    require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
    val start = TimeSource.Monotonic.markNow()
    val acquired = acquireInternal(TimeSource.Monotonic.markNow() + timeout)
    resilience.metrics.recordLockWait(semaphorePath, start.elapsedNow(), acquired)
    return acquired
  }

  fun tryAcquire(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = tryAcquire(timeUnitToDuration(timeout, timeUnit))

  /**
   * Releases this instance's most recently acquired live permit (LIFO). Returns
   * false when the consumed hold had already been lost or released by close();
   * throws [IllegalStateException] when this instance holds nothing at all.
   */
  fun release(): Boolean {
    val data = holds.pollFirst()
    if (data != null) {
      resilience.metrics.recordLockHold(semaphorePath, data.acquiredAt.elapsedNow())
      data.lease.close() // revoke deletes the entry, waking waiters
      return true
    }
    while (true) {
      val slots = dispossessedCount.load()
      if (slots <= 0) break
      if (dispossessedCount.compareAndSet(slots, slots - 1)) {
        logger.debug { "release() on $semaphorePath after the permit was lost or released by close()" }
        return false
      }
    }
    throw IllegalStateException("No permit is held on $semaphorePath by this instance")
  }

  /** Advisory: permits minus live holder/waiter entries, floored at zero. */
  fun availablePermits(): Int {
    checkCloseNotCalled()
    validatePermits()
    val entries = client.getChildCount(holdersPath, resilience.rpc).toInt()
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
  private fun validatePermits() {
    if (permitsValidated.load()) return
    var canonical = -1
    while (canonical == -1) {
      // -1 = key deleted between the failed CAS and the read; CAS again
      val txn =
        client.transaction(resilience.rpc) {
          If(permitsKey.doesNotExist)
          Then(permitsKey setTo permits)
        }
      canonical = if (txn.isSucceeded) permits else client.getValue(permitsKey, -1, resilience.rpc)
    }
    if (canonical != permits) {
      throw SemaphorePermitMismatchException(semaphorePath, permits, canonical)
    }
    permitsValidated.store(true)
  }

  @Suppress(
    "ReturnCount",
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
      if (deadline != null && deadline.hasPassedNow()) return false

      val entryKey = "$holdersPath/$clientId:${randomId(TOKEN_LENGTH)}"
      val attempt = Attempt(me, entryKey)
      val lease =
        AcquisitionLease(
          client,
          leaseTtlSecs,
          resilience.rpc,
          onTransient = { e ->
            recordException(e)
            reportLeaseEvent(LeaseEvent.Suspended(-1L, e))
          },
          onFatal = { cause -> onEntryFatal(attempt, cause) },
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

        while (true) {
          if (attempt.phase.load() == Phase.DEAD) {
            Thread.sleep(LEASE_HEAL_PAUSE_MS)
            continue@outer // fresh entry at the tail
          }
          if (deadline != null && deadline.hasPassedNow()) return false

          val snap = rankOf(entryKey)
            ?: run {
              // Own entry gone: the lease died in the window
              Thread.sleep(LEASE_HEAL_PAUSE_MS)
              continue@outer
            }
          if (snap.rank < permits) {
            // Admitted: publish the hold BEFORE claiming the phase (a fatal in
            // the win window must always find the hold — or the CAS failure
            // below rolls it back).
            val data = PermitData(lease, entryKey, me)
            attempt.holdData = data
            holds.addFirst(data)
            if (attempt.phase.compareAndSet(Phase.WAITING, Phase.HOLDING)) {
              acquired = true
              return true
            }
            holds.remove(data)
            Thread.sleep(LEASE_HEAL_PAUSE_MS)
            continue@outer
          }

          val latch = CountDownLatch(1)
          attempt.wake.store(latch)
          if (attempt.phase.load() == Phase.DEAD) latch.countDown() // fatal raced the install
          WaiterSupport.awaitPrefixDeletion(
            client,
            "$holdersPath/",
            resilience,
            latch,
            deadline,
            observedRevision = snap.observedRevision,
            shouldWake = {
              val now = rankOf(entryKey)
              now == null || now.rank < permits
            },
            reportRecovery = { event -> reportRecoveryEvent(event) },
            recordException = { e -> recordException(e) },
          )
          attempt.wake.store(null)
          // Loop: re-evaluate the rank (it only shrinks while the entry lives)
        }
      } finally {
        attempts -= attempt
        if (!acquired) {
          // Revoke deletes the entry (safe on an already-dead lease), waking waiters
          lease.close()
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
  private fun rankOf(entryKey: String): RankSnapshot? {
    val snapshot =
      client.getResponse(
        "$holdersPath/",
        getOption {
          isPrefix(true)
          withSortField(GetOption.SortTarget.CREATE)
          withSortOrder(GetOption.SortOrder.ASCEND)
        },
        resilience.rpc,
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
      dispossessedCount.incrementAndFetch()
      logger.warn(cause) { "Permit on $semaphorePath lost by $clientId (lease expired)" }
      recordException(cause ?: EtcdRecipeRuntimeException("Permit lease for $semaphorePath expired; permit lost"))
      reportLeaseEvent(LeaseEvent.Expired(-1L, cause))
      lostListeners.forEach { listener ->
        try {
          listener.onPermitLost(cause)
        } catch (e: Throwable) {
          logger.error(e) { "Exception in permit-lost listener" }
          recordException(e)
        }
      }
      if (interruptOnPermitLoss) attempt.owner.interrupt()
      data.lease.closeWithoutRevoke() // lease already gone; no RPC on this thread
    }
  }

  override fun doClose() {
    while (true) {
      val data = holds.pollFirst() ?: break
      dispossessedCount.incrementAndFetch()
      data.lease.close()
    }
    attempts.toList().forEach { attempt ->
      if (attempt.phase.compareAndSet(Phase.WAITING, Phase.DEAD)) {
        attempt.wake.load()?.countDown()
      }
    }
    lostListeners.clear()
  }

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
