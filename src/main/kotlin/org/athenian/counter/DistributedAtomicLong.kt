package org.athenian.counter

import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.CmpTarget
import org.athenian.*
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class DistributedAtomicLong(val url: String, counterName: String) : Closeable {

    private val counterPath = counterPath(counterName)
    private val semaphore = Semaphore(1, true)
    private val client = Client.builder().endpoints(url).build()
    private val kvClient = client.kvClient

    init {
        require(url.isEmpty()) { "URL cannot be empty" }
        require(counterName.isEmpty()) { "Counter name cannot be empty" }

        // Create counter if first time through
        createCounterIfNotPresent()
    }

    override fun close() {
        kvClient.close()
        client.close()
    }

    fun get(): Long = semaphore.withLock { kvClient.getLongValue(counterPath) ?: -1 }

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
                    println("Collisions: ${collisionCount.incrementAndGet()} Total: ${totalCount.get()} $count")
                    // Crude backoff for retry
                    Thread.sleep(Random.nextLong(count * 100L))
                    count++
                }
            } while (!txnResponse.isSucceeded)

            kvClient.getLongValue(counterPath) ?: -1
        }

    private fun createCounterIfNotPresent(): Boolean =
        // Run the transaction if the counter is not present
        if (kvClient.getValue(counterPath).kvs.isEmpty()) {
            val txn =
                kvClient.transaction {
                    If(equals(counterPath, CmpTarget.version(0)))
                    Then(puOp(counterPath, 0))
                }
            txn.isSucceeded
        } else {
            false
        }

    private fun applyCounterTransaction(amount: Long): TxnResponse {

        val kvlist = kvClient.getValue(counterPath).kvs
        val kv = if (kvlist.isNotEmpty()) kvlist[0] else throw InternalError("KeyValue List was empty")

        return this.kvClient.transaction {
            If(equals(counterPath, CmpTarget.modRevision(kv.modRevision)))
            Then(putOp(counterPath, kv.value.asLong + amount))
        }
    }

    companion object {
        private const val counterPrefix = "/counters"
        val collisionCount = AtomicLong()
        val totalCount = AtomicLong()

        private fun counterPath(counterName: String) =
            "${counterPrefix}${if (counterName.startsWith("/")) "" else "/"}$counterName"

        fun reset(url: String, counterName: String) {
            require(counterName.isEmpty()) { "Counter name cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient -> kvclient.delete(counterPath(counterName)) }
                }
        }
    }
}