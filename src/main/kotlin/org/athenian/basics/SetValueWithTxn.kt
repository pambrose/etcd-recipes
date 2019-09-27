package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.CmpTarget
import org.athenian.*
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/txntest"
    val debug = "/debug"

    fun checkForKey(kvclient: KV) {
        kvclient.transaction {
            If(equals(keyname, CmpTarget.version(0)))
            Then(putOp(debug, "Key $keyname not found"))
            Else(putOp(debug, "Key $keyname found"))
        }

        println("Debug value: ${kvclient.getStringValue(debug, "unset")}")
    }

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withKvClient { kvclient ->
                println("Deleting keys")
                kvclient.delete(keyname, debug)

                checkForKey(kvclient)
                kvclient.putValue(keyname, "Something")
                checkForKey(kvclient)
            }
        }
}