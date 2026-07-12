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
 * Shared wait-on-predecessor machinery for the hand-rolled locks (read-write lock,
 * semaphore): parks on [latch] until [key] is DELETEd (or found already absent —
 * the pre-live gap and every recovery are re-checked), someone else counts the
 * latch down (lease-fatal, close), or the deadline passes. Watch failures unpark
 * and throw. Follows the queue/barrier watcher discipline: recheck RPCs run on
 * the watch dispatcher thread, never on jetcd's event loop.
 */
internal object WaiterSupport {
  fun awaitKeyDeletion(
    client: Client,
    key: String,
    resilience: ResilienceConfig,
    latch: CountDownLatch,
    deadline: ComparableTimeMark?,
    reportRecovery: (WatchRecoveryEvent) -> Unit,
    recordException: (Throwable) -> Unit,
  ) {
    val failure = AtomicReference<Throwable?>()
    val recoveryListener =
      WatchRecoveryListener { event ->
        reportRecovery(event)
        when (event) {
          is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
            // The DELETE may have been lost while the stream was dead
            if (client.isKeyNotPresent(key, resilience.rpc)) latch.countDown()
          }

          is WatchRecoveryEvent.Failed -> {
            val cause = event.cause ?: EtcdRecipeRuntimeException("Watch on $key abandoned while waiting")
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
      key,
      watchOption { withNoPut(true) },
      resilience.watch,
      recoveryListener,
      resyncWith = null,
      { watchResponse ->
        if (watchResponse.events.any { it.eventType == WatchEvent.EventType.DELETE }) latch.countDown()
      },
    ) {
      // Pre-live gap: the key may have been deleted before the watch went live
      if (latch.count > 0 && client.isKeyNotPresent(key, resilience.rpc)) latch.countDown()
      if (deadline == null) {
        latch.await()
      } else {
        val remaining = -deadline.elapsedNow()
        if (remaining > Duration.ZERO) {
          latch.await(remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
      }
      failure.get()?.let { cause ->
        throw EtcdRecipeRuntimeException("Lock watch on $key failed while waiting", cause)
      }
    }
  }
}
