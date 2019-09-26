package org.athenian.counter

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.CmpTarget
import org.athenian.*
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class DistributedAtomicLong(val url: String, counterName: String) : Closeable {

    val executor = Executors.newSingleThreadExecutor()
    val counterPath = "$counterPrefix/$counterName"
    val semaphore = Semaphore(1, true)
    val latch = CountDownLatch(1)
    lateinit var kv: KV

    init {
        // Hold lock until initialization has taken place
        semaphore.acquire()
        executor.submit {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient ->

                        kv = kvclient

                        // Create counter if first time through
                        createCounterIfNotPresent()

                        semaphore.release()

                        latch.await()
                    }
                }
        }
    }

    override fun close() {
        latch.countDown()
        executor.shutdown()
    }

    fun get(): Long = semaphore.withLock { kv.getLongValue(counterPath) ?: -1 }

    fun increment(): Long = modifyCounterValue(1)

    fun decrement(): Long = modifyCounterValue(-1)

    fun add(amt: Long): Long = modifyCounterValue(amt)

    fun subtract(amt: Long): Long = modifyCounterValue(-amt)

    fun reset() {
        semaphore.withLock {
            kv.delete(counterPath)
            createCounterIfNotPresent()
        }
    }

    private fun modifyCounterValue(amt: Long): Long =
        semaphore.withLock {
            do {
                val txnResponse = applyCounterTransaction(amt)
                if (!txnResponse.isSucceeded)
                    println("Collision")
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
        val counterPrefix = "/counters"

        fun resetCounter(url: String, counterName: String) {
            val counterPath = "$counterPrefix/$counterName"
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient ->
                        kvclient.delete(counterPath)
                    }
                }
        }
    }

}