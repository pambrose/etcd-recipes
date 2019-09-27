package org.athenian.counter

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
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
    private val client: Client
    private val kv: KV

    init {
        client = Client.builder().endpoints(url).build()
        kv = client.kvClient

        // Create counter if first time through
        createCounterIfNotPresent()
    }

    override fun close() {
        kv.close()
        client.close()
    }

    fun get(): Long = semaphore.withLock { kv.getLongValue(counterPath) ?: -1 }

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

            kv.getLongValue(counterPath) ?: -1
        }

    private fun createCounterIfNotPresent(): Boolean =
        // Run the transaction if the counter is not present
        if (kv.get(counterPath).kvs.isEmpty()) {
            val txn =
                kv.transaction {
                    If(equals(counterPath, CmpTarget.version(0)))
                    Then(put(counterPath, 0))
                }
            txn.isSucceeded
        } else {
            false
        }

    private fun applyCounterTransaction(amount: Long): TxnResponse {
        val kvlist = kv.get(counterPath).kvs
        val kv = if (kvlist.isNotEmpty()) kvlist[0] else throw InternalError("KeyValue List was empty")

        return this.kv.transaction {
            If(equals(counterPath, CmpTarget.modRevision(kv.modRevision)))
            Then(put(counterPath, kv.value.asLong + amount))
        }
    }

    companion object {
        private val counterPrefix = "/counters"
        val collisionCount = AtomicLong()
        val totalCount = AtomicLong()

        fun counterPath(counterName: String) =
            "${counterPrefix}${if (counterName.startsWith("/")) "" else "/"}$counterName"

        fun reset(url: String, counterName: String) {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient ->
                        kvclient.delete(counterPath(counterName))
                    }
                }
        }
    }
}