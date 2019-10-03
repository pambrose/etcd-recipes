package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.CmpTarget
import org.athenian.jetcd.delete
import org.athenian.jetcd.equals
import org.athenian.jetcd.getStringValue
import org.athenian.jetcd.putOp
import org.athenian.jetcd.putValue
import org.athenian.jetcd.transaction
import org.athenian.jetcd.withKvClient

fun main() {
    val url = "http://localhost:2379"
    val keyname = "/txntest"
    val debug = "/debug"

    fun checkForKey(kvClient: KV) {
        kvClient.transaction {
            If(equals(keyname, CmpTarget.version(0)))
            Then(putOp(debug, "Key $keyname not found"))
            Else(putOp(debug, "Key $keyname found"))
        }

        println("Debug value: ${kvClient.getStringValue(debug, "unset")}")
    }

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withKvClient { kvClient ->
                println("Deleting keys")
                kvClient.delete(keyname, debug)

                checkForKey(kvClient)
                kvClient.putValue(keyname, "Something")
                checkForKey(kvClient)
            }
        }
}