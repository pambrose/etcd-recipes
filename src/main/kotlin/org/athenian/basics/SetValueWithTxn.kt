package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.CmpTarget
import org.athenian.utils.delete
import org.athenian.utils.equals
import org.athenian.utils.getStringValue
import org.athenian.utils.putOp
import org.athenian.utils.putValue
import org.athenian.utils.transaction
import org.athenian.utils.withKvClient

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