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
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getFirstChild
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

abstract class AbstractQueue(
  client: Client,
  val queuePath: String,
  val target: SortTarget,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  init {
    require(queuePath.isNotEmpty()) { "Queue path cannot be empty" }
  }

  @Suppress("LoopWithTooManyJumpStatements")
  fun dequeue(): ByteSequence {
    checkCloseNotCalled()

    // Loop instead of recursing on CAS-conflict retries: the previous
    // implementation called itself recursively, and each recursive frame
    // could allocate a new watcher (with its own dispatcher executor).
    // Under high contention the resulting churn was unbounded.
    while (true) {
      val childList = client.getFirstChild(queuePath, target).kvs
      if (childList.isNotEmpty()) {
        val child = childList.first()
        if (deleteRevKey(child)) {
          return child.value
        }
        logger.debug { "Lost CAS to concurrent consumer, retrying without watcher" }
        continue
      }

      // Queue is empty; wait under a single watcher. If the CAS delete fails
      // after waking up, loop and retry — withWatcher closes its dispatcher
      // before we retry, so no executor or watcher resources accumulate.
      val winner = waitForFirstChild() ?: continue
      if (deleteRevKey(winner)) return winner.value
    }
  }

  private fun waitForFirstChild(): KeyValue? {
    val watchLatch = CountDownLatch(1)
    val watchOption =
      watchOption {
        isPrefix(true)
        withNoDelete(true)
      }
    val keyFound = AtomicReference<KeyValue?>()
    val watchFailure = AtomicReference<Throwable?>()
    val recoveryListener = waiterRecoveryListener(watchLatch, keyFound, watchFailure)

    return client.withWatcher(
      queuePath,
      watchOption,
      resilience.watch,
      recoveryListener,
      resyncWith = null,
      { watchResponse ->
        synchronized(watchLatch) {
          for (watchEvent in watchResponse.events) {
            if (watchEvent.eventType == WatchEvent.EventType.PUT) {
              keyFound.compareAndSet(null, watchEvent.keyValue)
              watchLatch.countDown()
              break
            }
          }
        }
      },
    ) {
      // Poll once to UNBLOCK: a value may have arrived between watcher.use { } and the
      // watch going live in jetcd, and the watcher never delivers such a pre-live PUT,
      // so a poll is needed to count the latch down. The gRPC call must run *outside*
      // the synchronized block — the watcher callback runs on the jetcd Vert.x event
      // loop and also takes watchLatch's monitor, so holding it while a gRPC response
      // is pending would deadlock the event loop and never deliver the response.
      if (watchLatch.count > 0) {
        val waitingChildList = client.getFirstChild(queuePath, target).kvs
        if (waitingChildList.isNotEmpty()) {
          synchronized(watchLatch) {
            keyFound.set(waitingChildList.first())
            if (watchLatch.count > 0) watchLatch.countDown()
          }
        }
      }
      watchLatch.await()

      // STRICT ORDERING: whichever PUT the watcher observed first (in keyFound) is not
      // necessarily the head by sort order — a lower-priority key can be committed just
      // before a higher-priority one. So after waking, re-query the actual first child
      // by `target` and prefer it; this routes the wake-up path through the SAME
      // head-selection as the non-empty fast path above, guaranteeing the
      // highest-priority (KEY) / oldest (MOD) item. Fall back to the watcher's event only if the re-query is
      // empty (a concurrent consumer already took the head) — the deleteRevKey CAS and
      // the outer retry loop then still guarantee no loss or duplication.
      val head = client.getFirstChild(queuePath, target).kvs.firstOrNull() ?: keyFound.get()
      if (head == null) {
        watchFailure.get()?.let { cause ->
          throw EtcdRecipeRuntimeException("Queue watch on $queuePath failed while waiting for an item", cause)
        }
      }
      head
    }
  }

  // A PUT can land while the watch stream is fatally dead and never be delivered.
  // After each recovery, poll the head the same way the pre-live gap poll in
  // waitForFirstChild does (gRPC outside the latch monitor, on the watch dispatcher
  // thread). An abandoned recovery unparks the waiter with the failure recorded so
  // the caller errors out instead of parking forever.
  private fun waiterRecoveryListener(
    watchLatch: CountDownLatch,
    keyFound: AtomicReference<KeyValue?>,
    watchFailure: AtomicReference<Throwable?>,
  ): WatchRecoveryListener =
    WatchRecoveryListener { event ->
      reportRecoveryEvent(event)
      when (event) {
        is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
          val children = client.getFirstChild(queuePath, target).kvs
          if (children.isNotEmpty()) {
            synchronized(watchLatch) {
              keyFound.compareAndSet(null, children.first())
              if (watchLatch.count > 0) watchLatch.countDown()
            }
          }
        }

        is WatchRecoveryEvent.Failed -> {
          val cause = event.cause
            ?: EtcdRecipeRuntimeException("Watch on $queuePath abandoned while waiting for an item")
          watchFailure.set(cause)
          exceptionList.value += cause
          synchronized(watchLatch) {
            if (watchLatch.count > 0) watchLatch.countDown()
          }
        }

        is WatchRecoveryEvent.Suspended -> {
          // jetcd (transient) or the recovery loop (fatal) is already on it
        }
      }
    }

  private fun deleteRevKey(kv: KeyValue): Boolean =
    client.transaction {
      If(equalTo(kv.key, CmpTarget.modRevision(kv.modRevision)))
      Then(deleteOp(kv.key))
    }.isSucceeded

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
