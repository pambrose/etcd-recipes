package org.athenian.basics

import io.etcd.jetcd.Client
import org.athenian.utils.delete
import org.athenian.utils.getStringValue
import org.athenian.utils.putValue
import org.athenian.utils.repeatWithSleep
import org.athenian.utils.sleep
import org.athenian.utils.withKvClient
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
                    client.withKvClient { kvClient ->
                        println("Assigning $keyname = $keyval")
                        kvClient.putValue(keyname, keyval)

                        sleep(5.seconds)

                        println("Deleting $keyname")
                        kvClient.delete(keyname)
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
                    client.withKvClient { kvClient ->
                        repeatWithSleep(12) { _, start ->
                            val respval = kvClient.getStringValue(keyname, "unset")
                            println("Key $keyname = $respval after ${System.currentTimeMillis() - start}ms")
                        }
                    }
                }

        } finally {
            countdown.countDown()
        }
    }

    countdown.await()
}
