package org.athenian

import io.etcd.jetcd.Client
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
                            println("Assigning $keyname = $keyval")
                            kvclient.put(keyname.asByteSequence, keyval.asByteSequence).get()

                            sleep(5.seconds)

                            println("Deleting $keyname")
                            kvclient.delete(keyname.asByteSequence).get()
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
                    client.kvClient
                        .use { kvclient ->
                            delayedRepeat(12) { i, start ->
                                val resp = kvclient.get(keyname.asByteSequence).get()
                                val respval = resp.kvs.takeIf { it.size > 0 }?.get(0)?.value?.asString ?: "empty"
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
