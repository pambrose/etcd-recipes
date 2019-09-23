package org.athenian

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val countdown = CountDownLatch(2)
    val keyname = "foo"
    val keyval = "foobar"

    thread {
        try {
            sleep(3.seconds)

            Client.builder()
                .run {
                    endpoints(url)
                    build()
                }.use { client ->
                    client.kvClient
                        .use { kvclient ->
                            delayedRepeat(5) { i, start ->
                                val kv = keyval + i
                                println("Assigning $keyname = $kv")
                                kvclient.put(keyname.asByteSequence, kv.asByteSequence).get()

                                sleep(1.seconds)

                                println("Deleting $keyname")
                                kvclient.delete(keyname.asByteSequence).get()
                            }
                        }
                }
        } finally {
            countdown.countDown()
        }
    }

    thread {
        try {
            sleep(1.seconds)

            Client.builder()
                .run {
                    endpoints(url)
                    build()
                }.use { client ->
                    client.watchClient
                        .use { watch ->
                            val watchOptions =
                                WatchOption.newBuilder()
                                    .run {
                                        withRevision(0)
                                        build()
                                    }
                            println("Starting watch")
                            val watcher =
                                watch.watch(keyname.asByteSequence, watchOptions) { resp ->
                                    resp.events.forEach { event ->
                                        println("Watch event: ${event.eventType} ${event.keyValue.value.asString}")
                                    }
                                }

                            sleep(5.seconds)

                            println("Closing watch")
                            watcher.close()
                        }
                }
        } finally {
            countdown.countDown()
        }
    }

    countdown.await()
}
