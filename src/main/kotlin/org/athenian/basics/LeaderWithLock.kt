package org.athenian.basics

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import org.athenian.utils.getStringValue
import org.athenian.utils.lock
import org.athenian.utils.putValue
import org.athenian.utils.unlock
import org.athenian.utils.withKvClient
import org.athenian.utils.withLeaseClient
import org.athenian.utils.withLockClient
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.milliseconds
import kotlin.time.seconds

// Note: This is *not* the way to do an election

fun main() {
    val url = "http://localhost:2379"
    val count = 3
    val countdown = CountDownLatch(count)
    val keyname = "/lockedElection"

    repeat(count) { i ->
        thread {
            println("Started Thread $i")

            Client.builder().endpoints(url).build()
                .use { client ->
                    val keyval = "client$i"

                    sleep(3_000.random.milliseconds)

                    client.withLeaseClient { leaseClient ->
                        val lease = leaseClient.grant(10).get()

                        client.withLockClient { lock ->
                            println("Thread $i attempting to lock $keyname")
                            lock.lock(keyname, lease.id)
                            println("Thread $i locked $keyname")

                            client.withKvClient { kvClient ->
                                println("Thread $i assigning $keyval")
                                kvClient.putValue(keyname, keyval)

                                if (kvClient.getStringValue(keyname) == keyval)
                                    println("Thread $i is the leader")

                                // delete the key
                                //kvClient.delete(key)

                                println("Thread $i is waiting")
                                sleep(15.seconds)
                                println("Thread $i is done waiting")
                            }

                            println("Thread $i is unlocking")
                            lock.unlock(keyname)
                            println("Thread $i is done unlocking")
                        }
                    }
                }
            countdown.countDown()
        }
    }
    countdown.await()
}
