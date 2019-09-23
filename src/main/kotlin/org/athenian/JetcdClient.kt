package org.athenian

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val count = 3
    val countdown = CountDownLatch(count)
    val key = "/election4".asByteSequence

    repeat(count) { id ->
        println("Firing off $id")
        thread {
            Client.builder()
                .run {
                    endpoints("http://localhost:2379")
                    build()
                }
                .use { client ->

                    val value = "client$id".asByteSequence

                    client.watchClient
                        .use { watch ->

                            val watchOptions =
                                WatchOption.newBuilder()
                                    .run {
                                        withRevision(0)
                                        build()
                                    }

                            watch.watch(key, watchOptions) { resp ->
                                val event = resp.events[0]
                                println("Watch $id: ${event.eventType} ${event.keyValue.value.asString}")
                            }

                            sleep(Random.nextInt(3_000).milliseconds)

                            client.leaseClient
                                .use { leaseClient ->
                                    val lease = leaseClient.grant(10).get()

                                    client.lockClient.use { lock ->
                                        println("Thread $id attemoting to lock ${value.asString}")
                                        val lockresp = lock.lock(key, lease.id).get()
                                        println("Thread $id got lock ${value.asString}")
                                    }

                                    client.kvClient
                                        .use { kvclient ->

                                            kvclient.txn()
                                                .If()
                                                .Then()
                                                .Else()


                                            println("Thread $id assigning ${value.asString} ****")
                                            kvclient
                                                .put(key, value)
                                                .get()

                                            val response = kvclient.get(key).get()

                                            val keyval = response.kvs[0].value.asString
                                            if (keyval == value.asString)
                                                println("Thread $id is king")

                                            //println("Thread $id read value = $keyval")
                                            //Thread.sleep(2000)

                                            // delete the key
                                            //kvclient.delete(key).get()

                                            println("Thread $id is waiting")
                                            sleep(25.seconds)
                                            println("Thread $id is exiting")
                                        }
                                }

                        }
                }
            countdown.countDown()
        }
    }
    countdown.await()
}
