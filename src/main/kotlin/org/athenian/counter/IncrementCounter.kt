package org.athenian.counter

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.CmpTarget
import org.athenian.*


fun main() {

    val url = "http://localhost:2379"
    val counterName = "/DAL/counter1"

    fun createCounterIfRequired(kvclient: KV): Boolean {
        val kval = kvclient.get(counterName).kvs
        // Create the counter if it does not exist
        return if (kval.isEmpty()) {
            println("Creating counter")
            val txn =
                kvclient.transaction {
                    If(equals(counterName, CmpTarget.version(0)))
                    Then(put(counterName, 0))
                }
            txn.isSucceeded
        } else {
            //println("Not creating counter")
            true
        }
    }

    fun incrementCounter(kvclient: KV): Boolean {
        val kval = kvclient.get(counterName).kvs
        if (kval.isEmpty()) {
            println("Internal error")
        }

        val kv = kval[0]
        //println("Kval = ${kv.version} ${kv.modRevision} value: ${kv.value.asLong}")

        val txn =
            kvclient.transaction {
                If(equals(counterName, CmpTarget.modRevision(kv.modRevision)))
                Then(put(counterName, kv.value.asLong + 1))
            }
        return txn.isSucceeded
    }

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withLeaseClient { leaseClient ->
                client.withWatchClient { watchClient ->
                    client.withKvClient { kvclient ->

                        //kvclient.delete(counterName)

                        val createSuccess = createCounterIfRequired(kvclient)

                        do {
                            val txnSuccess = incrementCounter(kvclient)
                        } while (!txnSuccess)

                        println("${kvclient.getLongValue(counterName)}")
                    }
                }
            }
        }

}