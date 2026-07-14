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

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.isKeyNotPresent
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration

/**
 * Shared wait-on-DELETE machinery for the hand-rolled locks (read-write lock,
 * semaphore): parks on [CountDownLatch] until a DELETE fires (or [shouldWake]
 * says the predicate already holds — the pre-live gap and every recovery are
 * re-checked), someone else counts the latch down (lease-fatal, close), or the
 * deadline passes. Watch failures unpark and throw. Follows the queue/barrier
 * watcher discipline: recheck RPCs run on the watch dispatcher thread, never on
 * jetcd's event loop.
 *
 * Every waiter anchors its watch at `observedRevision + 1`, where `observedRevision`
 * is the store revision at which the caller's ranged read last saw the awaited
 * predecessor present. An un-anchored watch starts at whatever revision etcd assigns
 * when it processes the create — which races the pre-live recheck GET, so a DELETE
 * landing in that establishment window is missed by both and an unbounded `lock()`
 * parks forever. Anchoring replays every event after the observation, closing the
 * window; the pre-live recheck then only shortcuts the already-satisfied case. Pass
 * `observedRevision = 0` to opt out (watch from the current revision).
 */
internal object WaiterSupport {
  /** Waits for the DELETE of exactly [key]; the wake predicate is key absence. */
  @Suppress("LongParameterList")
  fun awaitKeyDeletion(
    client: Client,
    key: String,
    resilience: ResilienceConfig,
    latch: CountDownLatch,
    deadline: ComparableTimeMark?,
    observedRevision: Long,
    reportRecovery: (WatchRecoveryEvent) -> Unit,
    recordException: (Throwable) -> Unit,
  ) = await(
    client,
    key,
    watchOption { withNoPut(true).anchorAt(observedRevision) },
    resilience,
    latch,
    deadline,
    shouldWake = { client.isKeyNotPresent(key, resilience.rpc) },
    reportRecovery,
    recordException,
  )

  /**
   * Waits for ANY DELETE under [prefix] — rank-based admission (the semaphore)
   * cannot watch a single predecessor, so every deletion wakes the waiter and
   * [shouldWake] re-evaluates the full predicate on the pre-live gap and after
   * each recovery. Spurious wakes are safe: callers loop and re-check.
   */
  @Suppress("LongParameterList")
  fun awaitPrefixDeletion(
    client: Client,
    prefix: String,
    resilience: ResilienceConfig,
    latch: CountDownLatch,
    deadline: ComparableTimeMark?,
    observedRevision: Long,
    shouldWake: () -> Boolean,
    reportRecovery: (WatchRecoveryEvent) -> Unit,
    recordException: (Throwable) -> Unit,
  ) = await(
    client,
    prefix,
    watchOption { withNoPut(true).isPrefix(true).anchorAt(observedRevision) },
    resilience,
    latch,
    deadline,
    shouldWake,
    reportRecovery,
    recordException,
  )

  // Anchor the watch just past the revision at which the predecessor was observed
  // present, so its DELETE (necessarily at a later revision) is always (re)delivered.
  // observedRevision <= 0 means "no anchor" — subscribe at the current revision.
  private fun WatchOption.Builder.anchorAt(observedRevision: Long): WatchOption.Builder =
    apply { if (observedRevision > 0L) withRevision(observedRevision + 1) }

  @Suppress("LongParameterList")
  private fun await(
    client: Client,
    watchKey: String,
    option: WatchOption,
    resilience: ResilienceConfig,
    latch: CountDownLatch,
    deadline: ComparableTimeMark?,
    shouldWake: () -> Boolean,
    reportRecovery: (WatchRecoveryEvent) -> Unit,
    recordException: (Throwable) -> Unit,
  ) {
    val failure = AtomicReference<Throwable?>()
    val recoveryListener =
      WatchRecoveryListener { event ->
        reportRecovery(event)
        when (event) {
          is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
            // A DELETE may have been lost while the stream was dead
            if (shouldWake()) latch.countDown()
          }

          is WatchRecoveryEvent.Failed -> {
            val cause = event.cause ?: EtcdRecipeRuntimeException("Watch on $watchKey abandoned while waiting")
            failure.set(cause)
            recordException(cause)
            latch.countDown()
          }

          is WatchRecoveryEvent.Suspended -> {
            // jetcd (transient) or the recovery loop (fatal) is already on it
          }
        }
      }

    client.withWatcher(
      watchKey,
      option,
      resilience.watch,
      recoveryListener,
      resyncWith = null,
      { watchResponse ->
        if (watchResponse.events.any { it.eventType == WatchEvent.EventType.DELETE }) latch.countDown()
      },
    ) {
      // Pre-live gap: the awaited DELETE may have landed before the watch went live
      if (latch.count > 0 && shouldWake()) latch.countDown()
      if (deadline == null) {
        latch.await()
      } else {
        val remaining = -deadline.elapsedNow()
        if (remaining > Duration.ZERO) {
          latch.await(remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
      }
      failure.get()?.let { cause ->
        throw EtcdRecipeRuntimeException("Lock watch on $watchKey failed while waiting", cause)
      }
    }
  }
}
