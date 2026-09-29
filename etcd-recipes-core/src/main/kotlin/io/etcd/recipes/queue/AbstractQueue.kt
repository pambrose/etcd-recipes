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
import com.pambrose.common.util.ensureSuffix
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getFirstChild
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

abstract class AbstractQueue(
  client: Client,
  val queuePath: String,
  val target: SortTarget,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  init {
    require(queuePath.isNotEmpty()) { "Queue path cannot be empty" }
  }

  override val exceptionContext get() = "AbstractQueue[$queuePath]"

  // The latches of takes parked on an empty queue, so close() can release them.
  private val parkedTakes: MutableSet<CountDownLatch> = ConcurrentHashMap.newKeySet()

  fun dequeue(): ByteSequence = checkNotNull(takeEntry(null)) { "unbounded take returned empty" }.value

  /** Non-blocking take: the head item, or null when the queue is empty. */
  fun tryDequeue(): ByteSequence? = tryDequeueEntry()?.value

  // tryDequeue's taken entry (key and value); internal so the coroutine twins can restore it.
  internal fun tryDequeueEntry(): KeyValue? {
    checkCloseNotCalled()
    while (true) {
      val childList = client.getFirstChild(queuePath, target, resilience.rpc).kvs
      if (childList.isEmpty()) return null
      val child = childList.first()
      if (deleteRevKey(child)) return child
      // CAS lost to a concurrent consumer; retry until a win or the queue drains
    }
  }

  /** Bounded take: the head item, or null once [timeout] elapses without one. */
  fun poll(timeout: Duration): ByteSequence? {
    require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
    return takeEntry(TimeSource.Monotonic.markNow() + timeout)?.value
  }

  fun poll(
    timeout: Long,
    timeUnit: TimeUnit,
  ): ByteSequence? = poll(timeUnitToDuration(timeout, timeUnit))

  /**
   * Current number of items in the queue. etcd cannot push a count, so this issues a
   * range-count RPC on each call — a metrics gauge bound to it polls etcd on every scrape.
   */
  val size: Int get() = client.getChildCount(queuePath, resilience.rpc).toInt()

  // The single consumption loop: a null [deadline] never expires (the unbounded
  // take), otherwise the wait is bounded and an expired deadline yields null. Returns
  // the taken entry (key and value); internal so the coroutine twins can restore it.
  @Suppress("LoopWithTooManyJumpStatements", "ReturnCount")
  internal fun takeEntry(deadline: ComparableTimeMark?): KeyValue? {
    checkCloseNotCalled()
    val start = TimeSource.Monotonic.markNow() // dequeue latency = call → item in hand

    // Loop instead of recursing on CAS-conflict retries: a recursive frame per
    // retry could allocate a new watcher (with its own dispatcher executor).
    // Under high contention the resulting churn was unbounded.
    while (true) {
      // An item found after close() must not be deleted and handed to a closed instance
      checkCloseNotCalled()
      val firstChild = client.getFirstChild(queuePath, target, resilience.rpc)
      val childList = firstChild.kvs
      if (childList.isNotEmpty()) {
        val child = childList.first()
        if (deleteRevKey(child)) {
          resilience.metrics.recordQueue("dequeue", queuePath, start.elapsedNow())
          return child
        }
        logger.debug { "Lost CAS to concurrent consumer, retrying without watcher" }
        continue
      }

      if (deadline != null && deadline.hasPassedNow()) return null

      // Queue is empty; wait under a single watcher. If the CAS delete fails
      // after waking up, loop and retry — withWatcher closes its dispatcher
      // before we retry, so no executor or watcher resources accumulate. Anchor
      // the watch at the revision we observed the queue empty, so a PUT landing
      // in the watch-establishment window is still delivered (the pre-live poll
      // then only shortcuts the already-arrived case).
      val winner = waitForFirstChild(deadline, firstChild.header.revision) ?: continue
      if (deleteRevKey(winner)) {
        resilience.metrics.recordQueue("dequeue", queuePath, start.elapsedNow())
        return winner
      }
    }
  }

  private fun waitForFirstChild(
    deadline: ComparableTimeMark?,
    observedRevision: Long,
  ): KeyValue? {
    val watchLatch = CountDownLatch(1)
    parkedTakes += watchLatch
    try {
      // A close() that ran before this take registered found nothing to release
      if (closeCalled.load()) watchLatch.countDown()
      return awaitFirstChild(watchLatch, deadline, observedRevision)
    } finally {
      parkedTakes -= watchLatch
    }
  }

  private fun awaitFirstChild(
    watchLatch: CountDownLatch,
    deadline: ComparableTimeMark?,
    observedRevision: Long,
  ): KeyValue? {
    val watchOption =
      watchOption {
        if (observedRevision > 0L) withRevision(observedRevision + 1)
        isPrefix(true)
        withNoDelete(true)
      }
    val watchFailure = AtomicReference<Throwable?>(null)
    val recoveryListener = waiterRecoveryListener(watchLatch, watchFailure)

    // Watch this queue's children only. A bare-path prefix watch would also match a
    // sibling path that merely shares the string prefix (/jobs vs /jobs2/...).
    return client.withWatcher(
      queuePath.ensureSuffix("/"),
      watchOption,
      resilience.watch,
      recoveryListener,
      resyncWith = null,
      { watchResponse ->
        if (watchResponse.events.any { it.eventType == WatchEvent.EventType.PUT }) watchLatch.countDown()
      },
    ) {
      // Poll once to UNBLOCK: a value may have arrived between watcher.use { } and the
      // watch going live in jetcd, and the watcher never delivers such a pre-live PUT,
      // so a poll is needed to count the latch down.
      if (watchLatch.count > 0 && client.getFirstChild(queuePath, target, resilience.rpc).kvs.isNotEmpty())
        watchLatch.countDown()

      if (deadline == null) {
        watchLatch.await()
      } else {
        val remaining = -deadline.elapsedNow() // negative elapsed = time still left
        if (remaining > Duration.ZERO) {
          watchLatch.await(remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
      }
      if (closeCalled.load()) throw EtcdRecipeRuntimeException("Queue $queuePath closed while waiting for an item")

      // STRICT ORDERING: whichever PUT woke the watcher is not necessarily the head by
      // sort order — a lower-priority key can be committed just before a higher-priority
      // one. So after waking, re-query the actual first child by `target`; this routes
      // the wake-up path through the SAME head-selection as the non-empty fast path
      // above, guaranteeing the highest-priority (KEY) / oldest (MOD) item. An empty
      // re-query means a concurrent consumer already took the head: return null and
      // the outer loop re-reads. (Never fall back to the key the watcher saw — the
      // re-query is a linearizable read taken after the event, so a key it misses is
      // already gone.)
      val head = client.getFirstChild(queuePath, target, resilience.rpc).kvs.firstOrNull()
      if (head == null) {
        watchFailure.load()?.let { cause ->
          throw EtcdRecipeRuntimeException("Queue watch on $queuePath failed while waiting for an item", cause)
        }
      }
      head
    }
  }

  // A PUT can land while the watch stream is fatally dead and never be delivered.
  // After each recovery, poll the head the same way the pre-live gap poll in
  // waitForFirstChild does (on the watch dispatcher thread). An abandoned recovery
  // unparks the waiter with the failure recorded so the caller errors out instead of
  // parking forever.
  private fun waiterRecoveryListener(
    watchLatch: CountDownLatch,
    watchFailure: AtomicReference<Throwable?>,
  ): WatchRecoveryListener =
    WatchRecoveryListener { event ->
      withRecipeLoggingContext {
        reportRecoveryEvent(event)
        when (event) {
          is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
            if (client.getFirstChild(queuePath, target, resilience.rpc).kvs.isNotEmpty()) watchLatch.countDown()
          }

          is WatchRecoveryEvent.Failed -> {
            val cause = event.cause
              ?: EtcdRecipeRuntimeException("Watch on $queuePath abandoned while waiting for an item")
            watchFailure.store(cause)
            recordException(cause)
            watchLatch.countDown()
          }

          is WatchRecoveryEvent.Suspended -> {
            // jetcd (transient) or the recovery loop (fatal) is already on it
          }
        }
      }
    }

  /**
   * Puts back an entry that [takeEntry] took but that never reached its caller (a
   * coroutine cancelled just as the take succeeded), under its original key. A
   * key-ordered queue (priority) returns it to its place; a revision-ordered one (FIFO)
   * gets it at the tail. The create-only guard means it never overwrites a key. A
   * failure is recorded; the item is then lost.
   */
  @Suppress("TooGenericExceptionCaught")
  internal fun restoreTaken(entry: KeyValue) {
    try {
      val restored =
        client.transaction(resilience.rpc) {
          If(entry.key.asString.doesNotExist)
          Then(entry.key.asString setTo entry.value)
        }.isSucceeded
      if (!restored) logger.warn { "Not restoring ${entry.key.asString}: the key already exists" }
    } catch (e: Exception) {
      recordException(EtcdRecipeRuntimeException("Couldn't restore an item taken by a cancelled receive", e))
    }
  }

  // Releases takes parked on an empty queue; each then fails instead of waiting for an item.
  override fun doClose() {
    parkedTakes.forEach { it.countDown() }
  }

  private fun deleteRevKey(kv: KeyValue): Boolean =
    client.transaction(resilience.rpc) {
      If(equalTo(kv.key, CmpTarget.modRevision(kv.modRevision)))
      Then(deleteOp(kv.key))
    }.isSucceeded

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
