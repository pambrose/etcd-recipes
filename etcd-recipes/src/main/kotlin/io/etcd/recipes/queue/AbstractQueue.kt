/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import com.pambrose.common.util.isNotNull
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
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

abstract class AbstractQueue(
  client: Client,
  val queuePath: String,
  val target: SortTarget,
) : EtcdConnector(client) {
  init {
    require(queuePath.isNotEmpty()) { "Queue path cannot be empty" }
  }

  fun dequeue(): ByteSequence {
    checkCloseNotCalled()

    //  logger.info {client.getFirstChild(queuePath, target = target).kvs
    //           .map { p -> p.key.asString to p.value.asString }
    //           .joinToString("\n"))

    val childList = client.getFirstChild(queuePath, target).kvs

    if (childList.isNotEmpty()) {
      logger.info { "Child list not empty" }
      val child = childList.first()
      // If transactional delete fails, then just call self again
      return if (deleteRevKey(child))
        child.value
      else {
        logger.info { "Called self again" }
        dequeue()
      }
    }

    logger.info { "Child list was empty" }
    // No values available, so wait on them
    val watchLatch = CountDownLatch(1)
    // val trailingPath = queuePath.ensureSuffix("/")
    val watchOption =
      watchOption {
        isPrefix(true)
        withNoDelete(true)
      }
    val keyFound = AtomicReference<KeyValue?>()

    client.withWatcher(
      queuePath,
      watchOption,
      { watchResponse ->
        synchronized(watchLatch) {
          if (watchLatch.count > 0)
            logger.info { "watchResponse.events.size = ${watchResponse.events.size}" }
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
      // Query again in case a value arrived just before watch was created
      synchronized(watchLatch) {
        if (watchLatch.count > 0) {
          val waitingChildList = client.getFirstChild(queuePath, target).kvs
          if (waitingChildList.isNotEmpty()) {
            keyFound.compareAndSet(null, waitingChildList.first())
            watchLatch.countDown()
          }
        }
      }
      logger.info { "Waiting for watchLatch" }
      watchLatch.await()
    }

    val kv = keyFound.get()
    // If transactional delete fails, then just call self again
    return if (kv.isNotNull() && deleteRevKey(kv)) kv.value else dequeue()
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
