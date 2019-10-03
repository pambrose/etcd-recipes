package org.athenian.basics

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import org.athenian.utils.asPutOption
import org.athenian.utils.getStringValue
import org.athenian.utils.putValue
import org.athenian.utils.repeatWithSleep
import org.athenian.utils.withKvClient
import org.athenian.utils.withLeaseClient
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
                        repeatWithSleep(12) { _, start ->
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
