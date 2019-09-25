package org.athenian.basics

import io.etcd.jetcd.Client
import org.athenian.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

// Note: This is *not* the way to do an election

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val count = 3
    val countdown = CountDownLatch(count)
    val keyname = "/lockedElection"

    repeat(count) { id ->
        thread {
            println("Started Thread $id")

            Client.builder().endpoints(url).build()
                .use { client ->
                    val keyval = "client$id"

                    sleep(Random.nextInt(3_000).milliseconds)

                    client.leaseClient
                        .use { leaseClient ->
                            val lease = leaseClient.grant(10).get()

                            client.lockClient
                                .use { lock ->
                                    println("Thread $id attempting to lock $keyname")
                                    lock.lock(keyname, lease.id)
                                    println("Thread $id locked $keyname")

                                    client.kvClient
                                        .use { kvclient ->

                                            println("Thread $id assigning $keyval")
                                            kvclient.put(keyname, keyval)

                                            if (kvclient.getValue(keyname) == keyval)
                                                println("Thread $id is the leader")

                                            // delete the key
                                            //kvclient.delete(key)

                                            println("Thread $id is waiting")
                                            sleep(15.seconds)
                                            println("Thread $id is done waiting")
                                        }

                                    println("Thread $id is unlocking")
                                    lock.unlock(keyname)
                                    println("Thread $id is done unlocking")
                                }
                        }
                }
            countdown.countDown()
        }
    }
    countdown.await()
}
