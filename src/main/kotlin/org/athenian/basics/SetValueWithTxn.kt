package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.CmpTarget
import org.athenian.delete
import org.athenian.equals
import org.athenian.getStringValue
import org.athenian.putOp
import org.athenian.putValue
import org.athenian.transaction
import org.athenian.withKvClient
import kotlin.time.ExperimentalTime

@ExperimentalTime
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