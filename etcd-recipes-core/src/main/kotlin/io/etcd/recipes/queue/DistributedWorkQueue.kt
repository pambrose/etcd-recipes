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

package io.etcd.recipes.queue

import com.pambrose.common.time.timeUnitToDuration
import com.pambrose.common.util.length
import com.pambrose.common.util.randomId
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.LeaseListener
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.SelfHealingKeepAlive
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getFirstChild
import io.etcd.recipes.common.getKeyValuePairs
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.isLeaseNotFound
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.selfHealingKeepAlive
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class WorkQueueConfig
  @JvmOverloads
  constructor(
    /**
     * How long after a consumer dies (crash or partition) its claimed items become
     * reclaimable. A live consumer renews its lease, so processing may take longer
     * than this — the timeout bounds crash detection, not processing time.
     */
    val visibilityTimeoutSecs: Long = 30,
    /** Delivery attempts before an item is dead-lettered instead of redelivered. */
    val maxDeliveries: Int = 5,
    /** How often each consumer sweeps for orphaned claims. */
    val sweepInterval: Duration = 30.seconds,
  ) {
    init {
      require(visibilityTimeoutSecs >= 1) { "visibilityTimeoutSecs must be >= 1: $visibilityTimeoutSecs" }
      require(maxDeliveries >= 1) { "maxDeliveries must be >= 1: $maxDeliveries" }
      require(sweepInterval > Duration.ZERO) { "sweepInterval must be positive: $sweepInterval" }
    }
  }

/**
 * An at-least-once work queue (product idea #4). Unlike [DistributedQueue] — whose
 * take deletes the item before the consumer processes it, losing it on a crash — a
 * received item stays in etcd under `claimed/` until [WorkItem.ack] deletes it.
 *
 * The payload is never bound to the consumer's lease; only the claim *marker* is.
 * When a consumer dies, its markers expire with its lease while the payloads
 * survive, and any consumer's reclaim sweep moves the orphans back to the queue
 * (same key, so they return to their original FIFO position) — or to the
 * dead-letter space once [WorkQueueConfig.maxDeliveries] is exhausted.
 *
 * Delivery is at-least-once: a consumer that outlives its claim (partition longer
 * than the visibility timeout) may process an item that is being redelivered
 * elsewhere; its [WorkItem.ack] then returns false.
 */
class DistributedWorkQueue
  @JvmOverloads
  constructor(
    client: Client,
    val queuePath: String,
    val config: WorkQueueConfig = WorkQueueConfig(),
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    val clientId: String = defaultClientId(DistributedWorkQueue::class.simpleName!!),
  ) : EtcdConnector(client, resilience) {
  private val itemsPath = "$queuePath/items"
  private val claimedPath = "$queuePath/claimed"
  private val claimsPath = "$queuePath/claims"
  private val attemptsPath = "$queuePath/attempts"
  private val dlqPath = "$queuePath/dlq"
  private val delayedPath = "$queuePath/delayed"
  private val leaseListeners = CopyOnWriteArrayList<LeaseListener>()

  // The latches of receives parked on an empty queue, so close() can release them.
  private val parkedReceives: MutableSet<CountDownLatch> = ConcurrentHashMap.newKeySet()

  // Set when the consumer lease's healing is abandoned (cleared if it heals after all):
  // no claim can succeed then, so a receive fails instead of retrying the dead lease.
  private val leaseAbandoned = AtomicReference<Throwable?>(null)

  // True while consecutive reclaim sweeps fail, so a persistent failure is recorded once.
  private val sweepFailing = AtomicBoolean(false)

  init {
    require(queuePath.isNotEmpty()) { "Queue path cannot be empty" }
  }

  override val exceptionContext get() = "DistributedWorkQueue[$queuePath]"

  // The consumer lease all claim markers hang off. Self-healing for FUTURE claims
  // only: markers made under a dead lease are deliberately lost (that IS the
  // visibility contract — their items become reclaimable), and the trivial
  // establish hook re-grants so later claims work. Lazy so enqueue-only producers
  // never hold a lease or sweeper thread.
  private val consumerLeaseDelegate =
    lazy {
      sweeper // consumers also sweep
      client.selfHealingKeepAlive(
        config.visibilityTimeoutSecs.seconds,
        resilience.lease,
        leaseListener = { event -> onLeaseEvent(event) },
        rpc = resilience.rpc,
      ) { true }
    }
  private val consumerLease: SelfHealingKeepAlive by consumerLeaseDelegate

  private val sweeperDelegate =
    lazy {
      Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "workqueue-sweeper").apply { isDaemon = true }
      }.also { executor ->
        val period = config.sweepInterval.inWholeMilliseconds
        // Jitter the start so a fleet of consumers doesn't sweep in lockstep
        val initial = period + Random.nextLong(period / 2 + 1)
        executor.scheduleWithFixedDelay(::sweepSafely, initial, period, TimeUnit.MILLISECONDS)
      }
    }
  private val sweeper: ScheduledExecutorService by sweeperDelegate

  /** Registers a listener for the consumer lease's lifecycle events. */
  fun addLeaseListener(listener: LeaseListener) {
    leaseListeners += listener
  }

  fun removeLeaseListener(listener: LeaseListener) {
    leaseListeners -= listener
  }

  fun enqueue(value: String) = enqueue(value.asByteSequence)

  fun enqueue(value: Int) = enqueue(value.asByteSequence)

  fun enqueue(value: Long) = enqueue(value.asByteSequence)

  fun enqueue(value: ByteSequence) {
    checkCloseNotCalled()
    client.createUniqueKey(value, resilience.rpc) { newItemKey(itemsPath, System.currentTimeMillis()) }
  }

  fun enqueue(
    value: String,
    delay: Duration,
  ) = enqueue(value.asByteSequence, delay)

  /**
   * Enqueues [value] for delivery no earlier than [delay] from now (a zero or
   * negative delay enqueues immediately). Maturity is judged against client
   * clocks — skew between producers and consumers shifts delivery by the skew,
   * which is tolerable at visibility-timeout scale. An infinite delay is rejected.
   */
  fun enqueue(
    value: ByteSequence,
    delay: Duration,
  ) {
    checkCloseNotCalled()
    if (delay <= Duration.ZERO) {
      enqueue(value)
      return
    }
    // An infinite delay would overflow the ready time into a key that breaks every receive
    require(delay.isFinite()) { "Delay must be finite: $delay" }
    val readyAt = System.currentTimeMillis() + delay.inWholeMilliseconds
    client.createUniqueKey(value, resilience.rpc) { newItemKey(delayedPath, readyAt) }
  }

  /** Enqueues every value in one transaction (all-or-nothing), preserving order. */
  fun enqueueAll(values: Collection<ByteSequence>) {
    checkCloseNotCalled()
    if (values.isEmpty()) return
    client.createUniqueKeys(values.toList(), resilience.rpc) {
      val millis = System.currentTimeMillis()
      values.indices.map { index -> batchKeyFormat.format(itemsPath, millis, index, randomId(ITEM_KEY_SUFFIX_LENGTH)) }
    }
  }

  /** Blocking receive: waits until an item can be claimed. */
  fun receive(): WorkItem = checkNotNull(receiveWithDeadline(null)) { "unbounded receive returned empty" }

  /** Bounded receive: a claimed item, or null once [timeout] elapses without one. */
  fun receive(timeout: Duration): WorkItem? {
    require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
    return receiveWithDeadline(TimeSource.Monotonic.markNow() + timeout)
  }

  fun receive(
    timeout: Long,
    timeUnit: TimeUnit,
  ): WorkItem? = receive(timeUnitToDuration(timeout, timeUnit))

  /** Non-blocking receive: a claimed item, or null when nothing is claimable. */
  fun tryReceive(): WorkItem? {
    checkCloseNotCalled()
    promoteMatured()
    claimHead(null).item?.let { return it }
    reclaimOrphans()
    return claimHead(null).item
  }

  /** The current dead letters (items that exhausted [WorkQueueConfig.maxDeliveries]). */
  fun deadLetters(): List<DeadLetter> {
    checkCloseNotCalled()
    return client.getKeyValuePairs("$dlqPath/", getOption { isPrefix(true) }, resilience.rpc)
      .map { (key, value) ->
        val id = key.substringAfterLast('/')
        DeadLetter(id, value, client.getValue("$attemptsPath/$id", 0, resilience.rpc))
      }
  }

  /** Returns a dead letter to the queue with a fresh attempt count. */
  fun requeueDeadLetter(id: String): Boolean {
    checkCloseNotCalled()
    val kv = client.getResponse("$dlqPath/$id", rpc = resilience.rpc).kvs.firstOrNull() ?: return false
    return client.transaction(resilience.rpc) {
      If(equalTo(kv.key, CmpTarget.modRevision(kv.modRevision)))
      Then(
        "$itemsPath/$id" setTo kv.value,
        deleteOp(kv.key),
        deleteOp("$attemptsPath/$id".asByteSequence),
      )
    }.isSucceeded
  }

  /** Discards a dead letter permanently. */
  fun purgeDeadLetter(id: String): Boolean {
    checkCloseNotCalled()
    val kv = client.getResponse("$dlqPath/$id", rpc = resilience.rpc).kvs.firstOrNull() ?: return false
    return client.transaction(resilience.rpc) {
      If(equalTo(kv.key, CmpTarget.modRevision(kv.modRevision)))
      Then(deleteOp(kv.key), deleteOp("$attemptsPath/$id".asByteSequence))
    }.isSucceeded
  }

  inner class WorkItem internal constructor(
    val id: String,
    val value: ByteSequence,
    val attempt: Int,
    // The claim marker's create revision: identifies this claim, not just this consumer, so
    // a stale item can't ack or requeue a later claim of the same id by the same instance.
    private val claimRevision: Long,
  ) {
    private fun isStillClaimed(): Array<Cmp> {
      val claimKey = "$claimsPath/$id"
      return arrayOf(
        equalTo(claimKey, CmpTarget.value(clientId.asByteSequence)),
        equalTo(claimKey, CmpTarget.createRevision(claimRevision)),
      )
    }

    /**
     * Completes the item: deletes its claim, payload copy, and attempt counter in
     * one transaction, guarded on the claim still being this consumer's. Returns
     * false when the claim was lost (the visibility timeout passed and the item
     * was reclaimed) — the work may have been redone elsewhere.
     */
    fun ack(): Boolean {
      checkCloseNotCalled()
      return client.transaction(resilience.rpc) {
        If(*isStillClaimed())
        Then(
          deleteOp("$claimsPath/$id".asByteSequence),
          deleteOp("$claimedPath/$id".asByteSequence),
          deleteOp("$attemptsPath/$id".asByteSequence),
        )
      }.isSucceeded
    }

    /**
     * Returns the item to the queue early (attempts preserved). Once it has been
     * delivered [WorkQueueConfig.maxDeliveries] times, the next receive dead-letters it
     * instead of delivering it again.
     */
    fun requeue(): Boolean {
      checkCloseNotCalled()
      return client.transaction(resilience.rpc) {
        If(*isStillClaimed())
        Then(
          "$itemsPath/$id" setTo value,
          deleteOp("$claimsPath/$id".asByteSequence),
          deleteOp("$claimedPath/$id".asByteSequence),
        )
      }.isSucceeded
    }
  }

  data class DeadLetter(
    val id: String,
    val value: ByteSequence,
    val attempts: Int,
  )

  @Suppress("ReturnCount")
  private fun receiveWithDeadline(deadline: ComparableTimeMark?): WorkItem? {
    while (true) {
      checkCloseNotCalled()
      promoteMatured()
      claimHead(deadline).item?.let { return it }
      reclaimOrphans()
      val attempt = claimHead(deadline)
      attempt.item?.let { return it }
      if (deadline != null && deadline.hasPassedNow()) return null
      awaitItem(deadline, attempt.emptyAtRevision)
    }
  }

  // A claimed head, or the revision at which items/ was seen empty (0 when unknown).
  private class ClaimAttempt(
    val item: WorkItem?,
    val emptyAtRevision: Long,
  )

  // Claims the queue head atomically: guarded on the item's mod-revision, one
  // transaction deletes it from items/, copies it to claimed/, puts the leased
  // claim marker, and bumps the attempt counter. Reading the prior attempt count
  // outside the transaction is safe: only the claim winner writes it, and the
  // items/ guard picks exactly one winner per queue generation.
  //
  // An item already delivered maxDeliveries times (it came back through requeue()) is
  // dead-lettered here instead of delivered again; reclaimOrphans covers the crash path.
  @Suppress("ReturnCount", "TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
  private fun claimHead(deadline: ComparableTimeMark?): ClaimAttempt {
    while (true) {
      val response = client.getFirstChild(itemsPath, SortTarget.KEY, resilience.rpc)
      val head = response.kvs.firstOrNull() ?: return ClaimAttempt(null, response.header.revision)
      val id = head.key.asString.substringAfterLast('/')
      val delivered = client.getValue("$attemptsPath/$id", 0, resilience.rpc)
      if (delivered >= config.maxDeliveries) {
        deadLetterExhausted(head, id, delivered)
        continue
      }
      val attempt = delivered + 1
      val leaseId = consumerLeaseId()
      val txn =
        try {
          client.transaction(resilience.rpc) {
            If(equalTo(head.key, CmpTarget.modRevision(head.modRevision)))
            Then(
              deleteOp(head.key),
              "$claimedPath/$id" setTo head.value,
              "$claimsPath/$id".setTo(clientId, putOption { withLeaseId(leaseId) }),
              "$attemptsPath/$id" setTo attempt,
            )
          }
        } catch (e: Exception) {
          // The consumer lease can die between reading its id and committing; the
          // healer re-grants it shortly (its keep-alive stream reports NOT_FOUND on
          // the next renewal), so pace briefly and retry with the fresh lease — unless
          // this queue is closed, the healer gave up, or the receive's deadline passed.
          if (!e.isLeaseNotFound()) throw e
          if (!shouldRetryAfterLeaseLoss(e, deadline)) return ClaimAttempt(null, 0L)
          Thread.sleep(LEASE_HEAL_PAUSE_MS)
          continue
        }
      if (txn.isSucceeded) return ClaimAttempt(WorkItem(id, head.value, attempt, txn.header.revision), 0L)
      // Lost the head to a concurrent consumer; retry with the new head
    }
  }

  // Whether to retry a claim that failed because the consumer lease is gone. Throws when
  // no retry can succeed (this queue is closed, or the healer gave up); false once the
  // receive's deadline has passed.
  private fun shouldRetryAfterLeaseLoss(
    e: Exception,
    deadline: ComparableTimeMark?,
  ): Boolean {
    if (closeCalled.load()) throw EtcdRecipeRuntimeException("Work queue $queuePath closed while claiming", e)
    leaseAbandoned.load()?.let { cause ->
      throw EtcdRecipeRuntimeException("Consumer lease healing abandoned; $queuePath can't claim items", cause)
    }
    return deadline == null || !deadline.hasPassedNow()
  }

  // The consumer lease's current id, creating the lease (and sweeper) on first use. A
  // close() that raced that creation skipped the half-built lease, so it is released here
  // rather than claiming under a lease created after close.
  private fun consumerLeaseId(): Long {
    checkCloseNotCalled()
    val lease = consumerLease
    if (closeCalled.load()) {
      lease.close()
      if (sweeperDelegate.isInitialized()) sweeper.shutdownNow()
      throw EtcdRecipeRuntimeException("Work queue $queuePath closed while creating its consumer lease")
    }
    return lease.currentLeaseId
  }

  private fun deadLetterExhausted(
    head: KeyValue,
    id: String,
    delivered: Int,
  ) {
    logger.warn { "Dead-lettering $id after $delivered deliveries" }
    client.transaction(resilience.rpc) {
      If(equalTo(head.key, CmpTarget.modRevision(head.modRevision)))
      Then(deleteOp(head.key), "$dlqPath/$id" setTo head.value)
    }
  }

  // Finds claimed items whose claim marker expired (consumer died) and CAS-moves
  // each back to items/ — or to dlq/ once its attempts are exhausted. Races
  // between sweeping consumers are harmless: the claimed/ mod-revision guard
  // picks one winner, losers no-op.
  @Suppress("LoopWithTooManyJumpStatements")
  private fun reclaimOrphans() {
    val orphans = client.getKeyValuePairs("$claimedPath/", getOption { isPrefix(true) }, resilience.rpc)
    for ((fullKey, payload) in orphans) {
      val id = fullKey.substringAfterLast('/')
      if (client.isKeyPresent("$claimsPath/$id", resilience.rpc)) continue // still claimed

      val claimedKv = client.getResponse(fullKey, rpc = resilience.rpc).kvs.firstOrNull() ?: continue
      val attempts = client.getValue("$attemptsPath/$id", 0, resilience.rpc)
      val destination =
        if (attempts >= config.maxDeliveries) "$dlqPath/$id" else "$itemsPath/$id"
      if (attempts >= config.maxDeliveries) {
        logger.warn { "Dead-lettering $id after $attempts deliveries" }
      }
      client.transaction(resilience.rpc) {
        If(
          "$claimsPath/$id".doesNotExist,
          equalTo(claimedKv.key, CmpTarget.modRevision(claimedKv.modRevision)),
        )
        Then(deleteOp(claimedKv.key), destination setTo payload)
      }
    }
  }

  // A failure is recorded once per failing streak (the first failed sweep after a
  // successful one), so a persistent failure doesn't grow exceptions every interval.
  @Suppress("TooGenericExceptionCaught")
  private fun sweepSafely() {
    try {
      if (!closeCalled.load()) {
        promoteMatured()
        reclaimOrphans()
      }
      sweepFailing.store(false)
    } catch (e: Throwable) {
      if (sweepFailing.compareAndSet(false, true)) {
        logger.warn(e) { "Reclaim sweep failed; retrying every ${config.sweepInterval}" }
        recordException(e)
      } else {
        logger.debug(e) { "Reclaim sweep still failing" }
      }
    }
  }

  // Moves matured delayed items into items/ in ready-time order. A delayed key's
  // basename is "<readyMillis>-<uniq>", and the item is promoted under a key with the
  // same ready time — so promoted items sort by ready time among themselves and
  // interleave correctly with immediate items. The item key gets a fresh suffix and is
  // guarded as absent, so a promotion can never overwrite a queued item. CAS races
  // between promoting consumers pick one winner.
  private fun promoteMatured() {
    while (true) {
      val head = client.getFirstChild(delayedPath, SortTarget.KEY, resilience.rpc).kvs.firstOrNull() ?: return
      val readyAt = readyMillisOf(head.key.asString.substringAfterLast('/'))
      if (readyAt == null) {
        deadLetterMalformedDelayed(head)
        continue
      }
      if (readyAt > System.currentTimeMillis()) return
      val itemKey = newItemKey(itemsPath, readyAt)
      client.transaction(resilience.rpc) {
        If(equalTo(head.key, CmpTarget.modRevision(head.modRevision)), itemKey.doesNotExist)
        Then(deleteOp(head.key), itemKey setTo head.value)
      }
      // win or lose, re-read: the next head may also be mature
    }
  }

  // A delayed key whose ready time doesn't parse (written by another tool, or by an
  // overflowed delay) would sit at the head of delayed/ and break every receive. Move it
  // to the dead-letter space instead, where it stays visible, and record why.
  private fun deadLetterMalformedDelayed(head: KeyValue) {
    val basename = head.key.asString.substringAfterLast('/')
    val moved =
      client.transaction(resilience.rpc) {
        If(equalTo(head.key, CmpTarget.modRevision(head.modRevision)))
        Then(deleteOp(head.key), "$dlqPath/$basename" setTo head.value)
      }.isSucceeded
    if (moved) recordException(EtcdRecipeRuntimeException("Dead-lettered malformed delayed key ${head.key.asString}"))
  }

  // How long until the earliest delayed item matures, or null when none exist. A
  // malformed head is dead-lettered on the next promotion pass, so wake right away.
  private fun delayedHeadRemaining(): Duration? =
    client.getFirstChild(delayedPath, SortTarget.KEY, resilience.rpc).kvs.firstOrNull()?.let { head ->
      val readyAt = readyMillisOf(head.key.asString.substringAfterLast('/'))
      if (readyAt == null) Duration.ZERO else (readyAt - System.currentTimeMillis()).coerceAtLeast(0L).milliseconds
    }

  private fun readyMillisOf(basename: String): Long? = basename.substringBefore('-').toLongOrNull()

  private fun newItemKey(
    parentPath: String,
    millis: Long,
  ): String = keyFormat.format(parentPath, millis, randomId(ITEM_KEY_SUFFIX_LENGTH))

  // Parks until an item lands in items/ (or the deadline passes), on the resilient
  // watcher. Also wakes at least every sweepInterval so a parked consumer keeps
  // reclaiming even when nothing is enqueued.
  private fun awaitItem(
    deadline: ComparableTimeMark?,
    emptyAtRevision: Long,
  ) {
    val latch = CountDownLatch(1)
    parkedReceives += latch
    try {
      // A close() that ran before this receive registered found nothing to release
      if (closeCalled.load()) latch.countDown()
      awaitItem(latch, deadline, emptyAtRevision)
    } finally {
      parkedReceives -= latch
    }
  }

  private fun awaitItem(
    latch: CountDownLatch,
    deadline: ComparableTimeMark?,
    emptyAtRevision: Long,
  ) {
    val watchFailure = AtomicReference<Throwable?>(null)
    val recoveryListener = itemWaiterRecoveryListener(latch, watchFailure)

    client.withWatcher(
      "$itemsPath/",
      // Anchored at the revision items/ was seen empty, so a PUT that lands while the
      // watch is being established is still delivered.
      watchOption {
        if (emptyAtRevision > 0L) withRevision(emptyAtRevision + 1)
        isPrefix(true)
        withNoDelete(true)
      },
      resilience.watch,
      recoveryListener,
      resyncWith = null,
      { watchResponse ->
        if (watchResponse.events.any { it.eventType == WatchEvent.EventType.PUT }) latch.countDown()
      },
    ) {
      // Pre-live gap poll: an item may have landed before the watch went live
      if (latch.count > 0 && client.getFirstChild(itemsPath, SortTarget.KEY, resilience.rpc).kvs.isNotEmpty()) {
        latch.countDown()
      }
      // Wake when the deadline passes, the sweep interval elapses, or the earliest
      // delayed item matures — whichever comes first.
      var cap = config.sweepInterval
      delayedHeadRemaining()?.let { cap = minOf(cap, it) }
      val wait =
        if (deadline == null) cap else minOf(cap, -deadline.elapsedNow())
      if (wait > Duration.ZERO) {
        latch.await(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      }
      if (closeCalled.load()) throw EtcdRecipeRuntimeException("Work queue $queuePath closed while waiting for work")
      watchFailure.load()?.let { cause ->
        throw EtcdRecipeRuntimeException("Work-queue watch on $itemsPath failed while waiting", cause)
      }
    }
  }

  // After a watch recovery, polls items/ in case a PUT landed while the stream was dead;
  // an abandoned recovery unparks the waiter with the failure recorded.
  private fun itemWaiterRecoveryListener(
    latch: CountDownLatch,
    watchFailure: AtomicReference<Throwable?>,
  ): WatchRecoveryListener =
    WatchRecoveryListener { event ->
      withRecipeLoggingContext {
        reportRecoveryEvent(event)
        when (event) {
          is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
            if (client.getFirstChild(itemsPath, SortTarget.KEY, resilience.rpc).kvs.isNotEmpty()) {
              latch.countDown()
            }
          }

          is WatchRecoveryEvent.Failed -> {
            val cause = event.cause
              ?: EtcdRecipeRuntimeException("Watch on $itemsPath abandoned while waiting for work")
            watchFailure.store(cause)
            recordException(cause)
            latch.countDown()
          }

          is WatchRecoveryEvent.Suspended -> {
            // jetcd (transient) or the recovery loop (fatal) is already on it
          }
        }
      }
    }

  @Suppress("TooGenericExceptionCaught")
  private fun onLeaseEvent(event: LeaseEvent) {
    withRecipeLoggingContext {
      reportLeaseEvent(event)
      when (event) {
        is LeaseEvent.Suspended -> {
          recordException(event.cause)
        }

        is LeaseEvent.Expired -> {
          recordException(
          event.cause ?: EtcdRecipeRuntimeException("Consumer lease expired; outstanding claims are reclaimable"),
        )
        }

        is LeaseEvent.Failed -> {
          val cause =
            event.cause ?: EtcdRecipeRuntimeException("Consumer lease healing abandoned; claims will not renew")
          leaseAbandoned.store(cause)
          recordException(cause)
        }

        is LeaseEvent.Restored -> {
          leaseAbandoned.store(null)
          logger.info { "Consumer lease healed: ${event.oldLeaseId} -> ${event.newLeaseId}" }
        }
      }
      leaseListeners.forEach { listener ->
        try {
          listener.onLeaseEvent(event)
        } catch (e: Throwable) {
          logger.error(e) { "Exception in lease listener" }
          recordException(e)
        }
      }
    }
  }

  override fun doClose() {
    // Release parked receives; each then fails instead of claiming for a closed instance
    parkedReceives.forEach { it.countDown() }
    if (sweeperDelegate.isInitialized()) {
      sweeper.shutdownNow()
    }
    if (consumerLeaseDelegate.isInitialized()) {
      // Revokes the consumer lease: our unacked claims become reclaimable at once
      consumerLease.close()
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
    private val keyFormat = "%s/%0${Long.MAX_VALUE.length}d-%s"
    private val batchKeyFormat = "%s/%0${Long.MAX_VALUE.length}d-%05d-%s"
    private const val LEASE_HEAL_PAUSE_MS = 250L
  }
}
