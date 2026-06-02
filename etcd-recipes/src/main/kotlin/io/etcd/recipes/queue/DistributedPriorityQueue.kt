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

import com.pambrose.common.util.sleep
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getLastChild
import io.etcd.recipes.common.lessThan
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

fun <T> withDistributedPriorityQueue(
  client: Client,
  queuePath: String,
  minimumWaitTime: Duration = 0.milliseconds,
  receiver: DistributedPriorityQueue.() -> T,
): T = DistributedPriorityQueue(client, queuePath, minimumWaitTime).use { it.receiver() }

class DistributedPriorityQueue(
  client: Client,
  queuePath: String,
  val minimumWaitTime: Duration,
) : AbstractQueue(client, queuePath, SortTarget.KEY) {
  private var lastWriteTime = TimeSource.Monotonic.markNow()

  fun enqueue(
    value: String,
    priority: Int,
  ) = enqueue(value.asByteSequence, priority.toUShort())

  fun enqueue(
    value: Int,
    priority: Int,
  ) = enqueue(value.asByteSequence, priority.toUShort())

  fun enqueue(
    value: Long,
    priority: Int,
  ) = enqueue(value.asByteSequence, priority.toUShort())

  fun enqueue(
    value: ByteSequence,
    priority: Int,
  ) = enqueue(value, priority.toUShort())

  fun enqueue(
    value: String,
    priority: UShort,
  ) = enqueue(value.asByteSequence, priority)

  fun enqueue(
    value: Int,
    priority: UShort,
  ) = enqueue(value.asByteSequence, priority)

  fun enqueue(
    value: Long,
    priority: UShort,
  ) = enqueue(value.asByteSequence, priority)

  fun enqueue(
    value: ByteSequence,
    priority: UShort,
  ) {
    checkCloseNotCalled()
    val prefix = "%s/%05d".format(queuePath, priority.toInt())
    newSequentialKV(prefix, value)
  }

  private fun newSequentialKV(
    prefix: String,
    value: ByteSequence,
  ) {
    synchronized(this) {
      val elapsed = lastWriteTime.elapsedNow()
      if (elapsed < minimumWaitTime) {
        sleep(minimumWaitTime - elapsed)
      }

      // Optimistic-concurrency retry loop. The CAS below is guarded on the base
      // key's mod revision, so two producers enqueuing at the same priority from
      // different instances can compute the same sequence number and one loses the
      // CAS. That is ordinary contention, not an error: re-read getLastChild for a
      // fresh sequence number and a fresh header revision, then retry — mirroring
      // dequeue()'s retry-on-CAS-conflict loop. synchronized(this) only serializes
      // writers within this JVM; cross-instance correctness comes from this retry.
      repeat(MAX_ENQUEUE_ATTEMPTS) { attempt ->
        val resp = client.getLastChild(prefix, SortTarget.KEY)
        val kvs = resp.kvs

        var newSeqNum = 0
        if (kvs.isNotEmpty()) {
          val fields = kvs.first().key.asString.split("/")
          newSeqNum = fields[(fields.size) - 1].toInt() + 1
        }

        val txn =
          client.transaction {
            val newKey = "%s/%016d".format(prefix, newSeqNum)
            val baseKey = "__$prefix"
            If(lessThan(baseKey, CmpTarget.modRevision(resp.header.revision + 1)))
            Then(baseKey setTo "", newKey setTo value)
          }

        if (txn.isSucceeded) {
          lastWriteTime = TimeSource.Monotonic.markNow()
          return
        }

        logger.debug { "Lost enqueue CAS for $prefix on attempt ${attempt + 1}, retrying" }
      }

      throw EtcdRecipeRuntimeException("Failed to enqueue to $queuePath after $MAX_ENQUEUE_ATTEMPTS attempts")
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    // Upper bound on optimistic-CAS retries before surfacing failure. Each round
    // guarantees forward progress (one same-priority producer always wins), so this
    // only trips under pathological sustained contention; bounded rather than
    // unbounded so a stuck enqueue cannot hold the instance monitor against close().
    private const val MAX_ENQUEUE_ATTEMPTS = 50
  }
}
