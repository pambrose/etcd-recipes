package org.athenian

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.PutOption
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/txntest"
    val debug = "/debug"

    fun checkForKey(kvclient: KV) {
        kvclient.txn()
            .run {
                If(Cmp(keyname.asByteSequence, Cmp.Op.EQUAL, CmpTarget.version(0)))
                Then(Op.put(debug.asByteSequence, "Key $keyname not found".asByteSequence, PutOption.DEFAULT))
                Else(Op.put(debug.asByteSequence, "Key $keyname found".asByteSequence, PutOption.DEFAULT))
                commit()
            }.get()

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