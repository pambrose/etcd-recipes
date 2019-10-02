package org.athenian.counter

import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.CmpTarget
import org.athenian.utils.asLong
import org.athenian.utils.delete
import org.athenian.utils.equals
import org.athenian.utils.getLongValue
import org.athenian.utils.getResponse
import org.athenian.utils.putOp
import org.athenian.utils.random
import org.athenian.utils.sleep
import org.athenian.utils.transaction
import org.athenian.utils.withKvClient
import org.athenian.utils.withLock
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds

@ExperimentalTime
class DistributedAtomicLong(val url: String, val counterPath: String) : Closeable {

    private val semaphore = Semaphore(1, true)
    private val client = lazy { Client.builder().endpoints(url).build() }
    private val kvClient = lazy { client.value.kvClient }

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }

        // Create counter if first time through
        createCounterIfNotPresent()
    }

    fun get(): Long = semaphore.withLock { kvClient.getLongValue(counterPath) ?: -1L }

    fun increment(): Long = modifyCounterValue(1)

    fun decrement(): Long = modifyCounterValue(-1)

    fun add(value: Long): Long = modifyCounterValue(value)

    fun subtract(value: Long): Long = modifyCounterValue(-value)

    private fun modifyCounterValue(value: Long): Long =
        semaphore.withLock {
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

    private fun createCounterIfNotPresent(): Boolean =
        // Run the transaction if the counter is not present
        if (kvClient.getResponse(counterPath).kvs.isEmpty()) {
            val txn =
                kvClient.transaction {
                    If(equals(counterPath, CmpTarget.version(0)))
                    Then(putOp(counterPath, 0L))
                }
            txn.isSucceeded
        } else {
            false
        }

    private fun applyCounterTransaction(amount: Long): TxnResponse {
        val kvlist = kvClient.getResponse(counterPath).kvs
        val kv = if (kvlist.isNotEmpty()) kvlist[0] else throw IllegalStateException("KeyValue List was empty")

        val l = kv.value.asLong
        return this.kvClient.transaction {
            If(equals(counterPath, CmpTarget.modRevision(kv.modRevision)))
            Then(putOp(counterPath, kv.value.asLong + amount))
        }
    }

    override fun close() {
        if (kvClient.isInitialized())
            kvClient.value.close()

        if (client.isInitialized())
            client.value.close()
    }

    companion object Static {
        val collisionCount = AtomicLong()
        val totalCount = AtomicLong()

        fun reset(url: String, counterPath: String) {
            require(counterPath.isNotEmpty()) { "Counter path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        kvClient.delete(counterPath)
                    }
                }
        }
    }
}