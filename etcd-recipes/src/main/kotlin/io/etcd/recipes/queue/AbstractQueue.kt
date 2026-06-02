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
) : EtcdConnector(client) {
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

    return client.withWatcher(
      queuePath,
      watchOption,
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
      // Query again in case a value arrived just before watch was created.
      // The gRPC call must run *outside* the synchronized block: the watcher
      // callback executes on the jetcd Vert.x event loop and also takes
      // watchLatch's monitor, so holding it while a gRPC response is pending
      // deadlocks the event loop and never delivers the response.
      //
      // When the receiver poll wins the race (watchLatch is still latched),
      // it is authoritative for ordering — it returns the queue's actual
      // first item by mod revision, which may pre-date any PUT the watcher
      // saw if PUTs slipped in between watcher.use { } and the watch actually
      // being live in jetcd, so we override keyFound and dequeue the
      // highest-priority/oldest item. But this override only runs while the
      // latch is still up: under a producer/watcher race where the watch
      // callback fires first, keyFound already holds whatever PUT the watcher
      // observed and dequeue returns that available item rather than the
      // strict highest-priority/oldest one. Delivery is still safe regardless
      // of which side wins — no loss or duplication — because the
      // deleteRevKey CAS rejects a stale candidate and the outer retry loop
      // re-reads the queue.
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
      keyFound.get()
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
