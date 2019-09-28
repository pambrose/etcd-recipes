package org.athenian.basics

import io.etcd.jetcd.Client
import org.athenian.asPutOption
import org.athenian.getStringValue
import org.athenian.putValue
import org.athenian.repeatWithSleep
import org.athenian.sleep
import org.athenian.withKvClient
import org.athenian.withLeaseClient
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
                    client.withLeaseClient { leaseClient ->
                        client.withKvClient { kvClient ->
                            println("Assigning $keyname = $keyval")
                            val lease = leaseClient.grant(5).get()
                            kvClient.putValue(keyname, keyval, lease.asPutOption)
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
                    client.withKvClient { kvClient ->
                        repeatWithSleep(12) { i, start ->
                            val kval = kvClient.getStringValue(keyname, "unset")
                            println("Key $keyname = $kval after ${System.currentTimeMillis() - start}ms")
                        }
                    }
                }

        } finally {
            countdown.countDown()
        }
    }

    countdown.await()
}
