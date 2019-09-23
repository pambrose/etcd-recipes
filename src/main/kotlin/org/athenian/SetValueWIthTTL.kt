package org.athenian

import io.etcd.jetcd.Client
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val countdown = CountDownLatch(2)
    val keyname = "foo"
    val keyval = "foobar"

    thread {
        try {
            Thread.sleep(3_000)

            Client.builder()
                .run {
                    endpoints(url)
                    build()
                }.use { client ->
                    client.leaseClient
                        .use { leaseClient ->
                            client.kvClient
                                .use { kvclient ->
                                    println("Assigning $keyname = keyval")
                                    val lease = leaseClient.grant(5).get()
                                    kvclient.put(keyname.asByteSequence, keyval.asByteSequence, lease.asPutOption).get()
                                }
                        }

                }
        } finally {
            countdown.countDown()
        }
    }

    thread {
        try {
            Client.builder()
                .run {
                    endpoints(url)
                    build()
                }.use { client ->
                    client.getKVClient()
                        .use { kvClient ->
                            delayedRepeat(12) { i, start ->
                                val resp = kvClient.get(keyname.asByteSequence).get()
                                val keyval = resp.kvs.takeIf { it.size > 0 }?.get(0)?.value?.asString ?: "empty"
                                println("Key $keyname = $keyval after ${System.currentTimeMillis() - start}ms")
                            }
                        }
                }

        } finally {
            countdown.countDown()
        }
    }

    countdown.await()
}
