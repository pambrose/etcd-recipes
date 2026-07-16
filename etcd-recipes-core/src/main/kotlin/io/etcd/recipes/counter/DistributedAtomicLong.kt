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

package io.etcd.recipes.counter

import com.pambrose.common.util.random
import com.pambrose.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.CmpTarget
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.asLong
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.milliseconds

@JvmOverloads
fun <T> withDistributedAtomicLong(
  client: Client,
  counterPath: String,
  default: Long = 0L,
  receiver: DistributedAtomicLong.() -> T,
): T = DistributedAtomicLong(client, counterPath, default).use { it.receiver() }

class DistributedAtomicLong
@JvmOverloads
constructor(
  client: Client,
  val counterPath: String,
  private val default: Long = 0L,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  init {
    require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }
  }

  /**
   * Initialize the counter in etcd if it does not already exist.
   *
   * Previously the constructor performed this work in its `init` block,
   * which made the counter impossible to construct without a live etcd —
   * a unit-testable constructor is now possible. start() is invoked
   * automatically on the first call to a method that needs the counter
   * present, so existing call sites that just construct + use continue
   * to work; an explicit start() is also accepted.
   */
  fun start(): DistributedAtomicLong {
    ensureStarted()
    return this
  }

  fun get(): Long {
    ensureStarted()
    return client.getValue(counterPath, -1L, resilience.rpc)
  }

  fun increment(): Long = modifyCounterValue(1L)

  fun decrement(): Long = modifyCounterValue(-1L)

  fun add(value: Long): Long = modifyCounterValue(value)

  fun subtract(value: Long): Long = modifyCounterValue(-value)

  // Lazy init for thread-safe first-use. The CAS ensures exactly one thread
  // performs the actual etcd transaction; concurrent callers wait on the
  // start-complete monitor so they only proceed after the counter row is
  // committed. The original design ran this in the constructor; the lazy
  // form keeps construction I/O-free without losing the happens-before
  // guarantee that other threads relied on.
  private fun ensureStarted() {
    checkCloseNotCalled()
    if (startCalled.compareAndSet(false, true)) {
      try {
        createCounterIfNotPresent()
      } finally {
        startThreadComplete.set(true)
      }
    } else {
      startThreadComplete.waitUntilTrue()
    }
  }

  private fun modifyCounterValue(value: Long): Long {
    ensureStarted()
    checkCloseNotCalled()
    var count = 1
    while (true) {
      val (txnResponse, committedValue) = applyCounterTransaction(value)
      if (txnResponse.isSucceeded) {
        // Return the value we just wrote — not a separate GET. The previous
        // implementation re-read counterPath after a successful CAS; under
        // contention another writer could mutate the counter between the CAS
        // and the GET, so callers received a value they did not commit.
        return committedValue
      }
      // Crude backoff for retry
      sleep((count * 100).random().milliseconds)
      count++
    }
  }

  // The If(doesNotExist) predicate is itself the atomic "is it absent?" check, so the
  // prior getResponse(counterPath).kvs.isEmpty() GET was a redundant extra round-trip.
  // Returns true if this call created the counter, false if it already existed.
  private fun createCounterIfNotPresent(): Boolean =
    client
      .transaction {
        If(counterPath.doesNotExist)
        Then(counterPath setTo default)
      }.isSucceeded

  private fun applyCounterTransaction(amount: Long): Pair<TxnResponse, Long> {
    val kvList: List<KeyValue> = client.getResponse(counterPath, rpc = resilience.rpc).kvs
    check(kvList.isNotEmpty()) { "Empty KeyValue list" }
    val kv = kvList.first()
    val newValue = kv.value.asLong + amount
    val txn =
      client.transaction(resilience.rpc) {
        If(equalTo(counterPath, CmpTarget.modRevision(kv.modRevision)))
        Then(counterPath setTo newValue)
      }
    return txn to newValue
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    @JvmStatic
    fun delete(
      client: Client,
      counterPath: String,
    ) {
      require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }
      client.deleteKey(counterPath)
    }
  }
}
