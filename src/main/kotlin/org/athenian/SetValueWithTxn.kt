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


    fun runTxn(kvclient: KV) {
        val txresp =
            kvclient.txn()
                .run {
                    If(Cmp(keyname.asByteSequence, Cmp.Op.EQUAL, CmpTarget.version(0)))
                    Then(Op.put(debug.asByteSequence, "EQUAL".asByteSequence, PutOption.DEFAULT))
                    Else(Op.put(debug.asByteSequence, "NOT EQUAL".asByteSequence, PutOption.DEFAULT))
                    commit()
                }.get()

        val resp = kvclient.get(debug.asByteSequence).get()
        val kval = resp.kvs.takeIf { it.size > 0 }?.get(0)?.value?.asString ?: ""

        println("Debug value: $kval")
    }

    Client.builder()
        .run {
            endpoints(url)
            build()
        }
        .use { client ->

            client.kvClient
                .use { kvclient ->
                    println("Deleting keys")
                    kvclient.delete(keyname)
                    kvclient.delete(debug)

                    runTxn(kvclient)
                    kvclient.put(keyname, "Something")
                    runTxn(kvclient)
                }
        }
}