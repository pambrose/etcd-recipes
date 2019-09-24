package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.CmpTarget
import org.athenian.delete
import org.athenian.getValue
import org.athenian.put
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/txntest"
    val debug = "/debug"

    fun checkForKey(kvclient: KV) {
        kvclient.txn()
            .run {
                If(org.athenian.equals(keyname, CmpTarget.version(0)))
                Then(put(debug, "Key $keyname not found"))
                Else(put(debug, "Key $keyname found"))
                commit().get()
            }

        println("Debug value: ${kvclient.getValue(debug)}")
    }

    Client.builder().endpoints(url).build()
        .use { client ->
            client.kvClient
                .use { kvclient ->
                    println("Deleting keys")
                    kvclient.delete(keyname, debug)

                    checkForKey(kvclient)
                    kvclient.put(keyname, "Something")
                    checkForKey(kvclient)
                }
        }
}