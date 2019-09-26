package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import org.athenian.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val countdown = CountDownLatch(2)
    val keyname = "/foo"
    val keyval = "foobar"

    thread {
        try {
            sleep(3.seconds)

            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient ->
                        repeatWithSleep(5) { i, start ->
                            val kv = keyval + i
                            println("Assigning $keyname = $kv")
                            kvclient.put(keyname, kv)

                            sleep(1.seconds)

                            println("Deleting $keyname")
                            kvclient.delete(keyname)
                        }
                    }
                }
        } finally {
            countdown.countDown()
        }
    }

    thread {
        try {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withWatchClient { watchClient ->
                        val watchOptions = WatchOption.newBuilder().withRevision(0).build()
                        println("Starting watch")
                        val watcher =
                            watchClient.watch(keyname.asByteSequence, watchOptions) { resp ->
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