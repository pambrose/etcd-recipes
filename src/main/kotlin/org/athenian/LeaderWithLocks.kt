package org.athenian

import io.etcd.jetcd.Client
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val count = 3
    val countdown = CountDownLatch(count)
    val keyname = "/election"

    repeat(count) { id ->
        thread {
            println("Started Thread $id")
            Client.builder()
                .run {
                    endpoints(url)
                    build()
                }
                .use { client ->

                    val keyval = "client$id"

                    sleep(Random.nextInt(3_000).milliseconds)

                    client.leaseClient
                        .use { leaseClient ->
                            val lease = leaseClient.grant(10).get()

                            client.lockClient
                                .use { lock ->
                                    println("Thread $id attempting to lock $keyname")
                                    val lockresp = lock.lock(keyname.asByteSequence, lease.id).get()
                                    println("Thread $id got lock $keyname")

                                    client.kvClient
                                        .use { kvclient ->
                                            /*
                                            kvclient.txn()
                                                .If(Cmp(keyname.asByteSequence, Cmp.Op.EQUAL, CmpTarget.value(keyval.asByteSequence)))
                                                .Then()
                                                .Else()
                                             */

                                            println("Thread $id assigning $keyval")
                                            kvclient
                                                .put(keyname.asByteSequence, keyval.asByteSequence)
                                                .get()

                                            val response = kvclient.get(keyname.asByteSequence).get()

                                            val respval = response.kvs[0].value.asString
                                            if (respval == keyval)
                                                println("Thread $id is the leader")

                                            // delete the key
                                            //kvclient.delete(key).get()

                                            println("Thread $id is waiting")
                                            sleep(25.seconds)
                                            println("Thread $id is done waiting")
                                        }

                                    println("Thread $id is unlocking")
                                    lock.unlock(keyname.asByteSequence).get()
                                    println("Thread $id is done unlocking")
                                }
                        }
                }
            countdown.countDown()
        }
    }
    countdown.await()
}
