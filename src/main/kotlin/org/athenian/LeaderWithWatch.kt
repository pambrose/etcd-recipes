package org.athenian

import io.etcd.jetcd.Client
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.PutOption
import io.etcd.jetcd.watch.WatchEvent
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val count = 1
    val countdown = CountDownLatch(count)
    val keyname = "/election"
    val debug = "/debug"

    repeat(count) { id ->
        thread {
            println("Started Thread $id")

            Client.builder().endpoints(url).build()
                .use { client ->
                    val keyval = "client$id"

                    sleep(Random.nextInt(3_000).milliseconds)

                    client.watchClient
                        .use { watchClient ->

                            println("Watching Thread $id")
                            watchClient.watch(keyname.asByteSequence) { resp ->
                                for (event in resp.events) {
                                    println("Watch event: ${event.eventType} ${event.keyValue.value.asString}")
                                    if (event.eventType == WatchEvent.EventType.DELETE) {
                                        println("Attempting to obtain lock")
                                    }
                                }
                            }

                            Thread.sleep(3000_000)

                            client.kvClient
                                .use { kvclient ->
                                    kvclient.txn()
                                        .run {
                                            If(Cmp(keyname.asByteSequence, Cmp.Op.EQUAL, CmpTarget.version(0)))
                                            Then(Op.put(debug.asByteSequence,
                                                        "EQUAL".asByteSequence,
                                                        PutOption.DEFAULT))
                                            Else(Op.put(debug.asByteSequence,
                                                        "NOT EQUAL".asByteSequence,
                                                        PutOption.DEFAULT))
                                            commit()
                                        }.get()


                                    println("Thread $id assigning $keyval")
                                    kvclient.put(keyname, keyval)

                                    val respval = kvclient.getValue(keyname)
                                    if (respval == keyval)
                                        println("Thread $id is the leader")

                                    // delete the key
                                    //kvclient.delete(key)

                                    println("Thread $id is waiting")
                                    sleep(25.seconds)
                                    println("Thread $id is done waiting")
                                }
                        }
                }
            countdown.countDown()
        }
    }
    countdown.await()
}
