/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.counter

import com.sudothought.common.concurrent.withLock
import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.CmpTarget
import io.etcd.recipes.common.*
import java.io.Closeable
import kotlin.time.milliseconds

class DistributedAtomicLong(val urls: List<String>,
                            val counterPath: String,
                            private val defaultValue: Long) : EtcdConnector(urls), Closeable {

    // For Java clients
    constructor(urls: List<String>, counterPath: String) : this(urls, counterPath, 0L)

    init {
        require(urls.isNotEmpty()) { "URLs cannot be empty" }
        require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }

        // Create counter if first time through
        createCounterIfNotPresent()
    }

    fun get(): Long = semaphore.withLock {
        checkCloseNotCalled()
        kvClient.getValue(counterPath, -1L)
    }

    fun increment(): Long = modifyCounterValue(1L)

    fun decrement(): Long = modifyCounterValue(-1L)

    fun add(value: Long): Long = modifyCounterValue(value)

    fun subtract(value: Long): Long = modifyCounterValue(-value)

    override fun close() {
        semaphore.withLock {
            super.close()
        }
    }

    private fun modifyCounterValue(value: Long): Long {
        checkCloseNotCalled()
        return semaphore.withLock {
            var count = 1
            //totalCount.incrementAndGet()
            do {
                val txnResponse = applyCounterTransaction(value)
                if (!txnResponse.isSucceeded) {
                    //println("Collisions: ${collisionCount.incrementAndGet()} Total: ${totalCount.get()} $count")
                    // Crude backoff for retry
                    sleep((count * 100).random.milliseconds)
                    count++
                }
            } while (!txnResponse.isSucceeded)

            // Return the latest value
            kvClient.getValue(counterPath, -1L)
        }
    }

    private fun createCounterIfNotPresent(): Boolean =
        // Run the transaction if the counter is not present
        if (kvClient.getResponse(counterPath).kvs.isEmpty()) {
            val txn =
                kvClient.transaction {
                    If(counterPath.doesNotExist)
                    Then(counterPath setTo defaultValue)
                }
            txn.isSucceeded
        } else {
            false
        }

    private fun applyCounterTransaction(amount: Long): TxnResponse =
        kvClient.transaction {
            val kvlist = kvClient.getResponse(counterPath).kvs
            val kv = if (kvlist.isNotEmpty()) kvlist[0] else throw IllegalStateException("KeyValue List was empty")
            If(equalTo(counterPath, CmpTarget.modRevision(kv.modRevision)))
            Then(counterPath setTo kv.value.asLong + amount)
        }

    companion object {
        //val collisionCount = AtomicLong()
        //val totalCount = AtomicLong()

        @JvmStatic
        fun delete(urls: List<String>, counterPath: String) {

            require(urls.isNotEmpty()) { "URLs cannot be empty" }
            require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }

            connectToEtcd(urls) { client ->
                client.withKvClient { kvClient -> kvClient.delete(counterPath) }
            }
        }
    }
}