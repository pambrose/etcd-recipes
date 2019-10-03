package org.athenian.basics

import com.sudothought.common.util.repeatWithSleep
import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import org.athenian.jetcd.delete
import org.athenian.jetcd.getStringValue
import org.athenian.jetcd.putValue
import org.athenian.jetcd.withKvClient
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
