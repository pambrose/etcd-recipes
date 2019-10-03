package org.athenian.basics

import com.sudothought.common.util.repeatWithSleep
import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import org.athenian.jetcd.asString
import org.athenian.jetcd.delete
import org.athenian.jetcd.putValue
import org.athenian.jetcd.watcher
import org.athenian.jetcd.withKvClient
import org.athenian.jetcd.withWatchClient
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

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
                    client.withKvClient { kvClient ->
                        repeatWithSleep(10) { i, _ ->
                            val kv = keyval + i
                            println("Assigning $keyname = $kv")
                            kvClient.putValue(keyname, kv)

                            sleep(1.seconds)

                            println("Deleting $keyname")
                            kvClient.delete(keyname)
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
                        println("Starting watch")
                        watchClient.watcher(keyname) { watchResponse ->
                            watchResponse.events
                                .forEach { watchEvent ->
                                    println("Watch event: ${watchEvent.eventType} ${watchEvent.keyValue.value.asString}")
                                }
                        }.use {
                            sleep(5.seconds)
                            println("Closing watch")
                        }
                        println("Closed watch")
                    }
                }
        } finally {
            countdown.countDown()
        }
    }

    countdown.await()
}
