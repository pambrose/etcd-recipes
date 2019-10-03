package org.athenian.basics

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import org.athenian.utils.asString
import org.athenian.utils.delete
import org.athenian.utils.putValue
import org.athenian.utils.repeatWithSleep
import org.athenian.utils.watcher
import org.athenian.utils.withKvClient
import org.athenian.utils.withWatchClient
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
