package org.athenian.basics

import io.etcd.jetcd.Client
import org.athenian.utils.getStringValue
import org.athenian.utils.lock
import org.athenian.utils.putValue
import org.athenian.utils.random
import org.athenian.utils.sleep
import org.athenian.utils.unlock
import org.athenian.utils.withKvClient
import org.athenian.utils.withLeaseClient
import org.athenian.utils.withLockClient
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

// Note: This is *not* the way to do an election

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val count = 3
    val countdown = CountDownLatch(count)
    val keyname = "/lockedElection"

    repeat(count) { id ->
        thread {
            println("Started Thread $id")

            Client.builder().endpoints(url).build()
                .use { client ->
                    val keyval = "client$id"

                    sleep(3_000.random.milliseconds)

                    client.withLeaseClient { leaseClient ->
                        val lease = leaseClient.grant(10).get()

                        client.withLockClient { lock ->
                            println("Thread $id attempting to lock $keyname")
                            lock.lock(keyname, lease.id)
                            println("Thread $id locked $keyname")

                            client.withKvClient { kvClient ->
                                println("Thread $id assigning $keyval")
                                kvClient.putValue(keyname, keyval)

                                if (kvClient.getStringValue(keyname) == keyval)
                                    println("Thread $id is the leader")

                                // delete the key
                                //kvClient.delete(key)

                                println("Thread $id is waiting")
                                sleep(15.seconds)
                                println("Thread $id is done waiting")
                            }

                            println("Thread $id is unlocking")
                            lock.unlock(keyname)
                            println("Thread $id is done unlocking")
                        }
                    }
                }
            countdown.countDown()
        }
    }
    countdown.await()
}
