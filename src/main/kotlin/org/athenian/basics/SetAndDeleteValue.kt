package org.athenian.basics

import io.etcd.jetcd.Client
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
                        println("Assigning $keyname = $keyval")
                        kvclient.putValue(keyname, keyval)

                        sleep(5.seconds)

                        println("Deleting $keyname")
                        kvclient.delete(keyname)
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
                    client.withKvClient { kvclient ->
                        repeatWithSleep(12) { i, start ->
                            val respval = kvclient.getStringValue(keyname, "unset")
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
