package org.athenian

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random

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

                            Thread.sleep(Random.nextLong(3_000))

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
                                            //kvClient.delete(key).get()

                                            println("Thread $id is waiting")
                                            Thread.sleep(25_000)
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
