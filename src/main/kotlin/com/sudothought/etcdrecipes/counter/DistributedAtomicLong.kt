/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package com.sudothought.etcdrecipes.counter

import com.sudothought.common.concurrent.withLock
import com.sudothought.common.delegate.AtomicDelegates
import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import com.sudothought.etcdrecipes.common.EtcdRecipeRuntimeException
import com.sudothought.etcdrecipes.jetcd.asLong
import com.sudothought.etcdrecipes.jetcd.delete
import com.sudothought.etcdrecipes.jetcd.equalTo
import com.sudothought.etcdrecipes.jetcd.getLongValue
import com.sudothought.etcdrecipes.jetcd.getResponse
import com.sudothought.etcdrecipes.jetcd.putOp
import com.sudothought.etcdrecipes.jetcd.transaction
import com.sudothought.etcdrecipes.jetcd.withKvClient
import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.CmpTarget
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.milliseconds

class DistributedAtomicLong(val url: String, val counterPath: String) : Closeable {

    private val semaphore = Semaphore(1, true)
    private val client = lazy { Client.builder().endpoints(url).build() }
    private val kvClient = lazy { client.value.kvClient }
    private var closeCalled by AtomicDelegates.atomicBoolean(false)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }

        // Create counter if first time through
        createCounterIfNotPresent()
    }

    fun get(): Long = semaphore.withLock {
        checkCloseNotCalled()
        kvClient.getLongValue(counterPath) ?: -1L
    }

    fun increment(): Long = modifyCounterValue(1)

    fun decrement(): Long = modifyCounterValue(-1)

    fun add(value: Long): Long = modifyCounterValue(value)

    fun subtract(value: Long): Long = modifyCounterValue(-value)

    private fun modifyCounterValue(value: Long): Long {
        checkCloseNotCalled()
        return semaphore.withLock {
            var count = 1
            totalCount.incrementAndGet()
            do {
                val txnResponse = applyCounterTransaction(value)
                if (!txnResponse.isSucceeded) {
                    //println("Collisions: ${collisionCount.incrementAndGet()} Total: ${totalCount.get()} $count")
                    // Crude backoff for retry
                    sleep((count * 100).random.milliseconds)
                    count++
                }
            } while (!txnResponse.isSucceeded)

            kvClient.getLongValue(counterPath) ?: -1
        }
    }

    private fun createCounterIfNotPresent(): Boolean =
        // Run the transaction if the counter is not present
        if (kvClient.getResponse(counterPath).kvs.isEmpty()) {
            val txn =
                kvClient.transaction {
                    If(equalTo(counterPath, CmpTarget.version(0)))
                    Then(putOp(counterPath, 0L))
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
            Then(putOp(counterPath, kv.value.asLong + amount))
        }

    private fun checkCloseNotCalled() {
        if (closeCalled) throw EtcdRecipeRuntimeException("close() already closed")
    }

    override fun close() {
        semaphore.withLock {
            if (!closeCalled) {
                if (kvClient.isInitialized())
                    kvClient.value.close()

                if (client.isInitialized())
                    client.value.close()

                closeCalled = true
            }
        }
    }

    companion object Static {
        val collisionCount = AtomicLong()
        val totalCount = AtomicLong()

        fun reset(url: String, counterPath: String) {
            require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient -> kvClient.delete(counterPath) }
                }
        }
    }
}