/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.basics

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.recipes.common.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.milliseconds
import kotlin.time.seconds

// Note: This is *not* the way to do an election

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/lockedElection"
    val count = 3
    val countdown = CountDownLatch(count)

    repeat(count) { i ->
        thread {
            println("Started Thread $i")

            Client.builder().endpoints(*urls.toTypedArray()).build()
                .use { client ->
                    val keyval = "client$i"

                    sleep(3_000.random.milliseconds)

                    client.withLeaseClient { leaseClient ->
                        val lease = leaseClient.grant(10).get()

                        client.withLockClient { lock ->
                            println("Thread $i attempting to lock $path")
                            lock.lock(path, lease.id)
                            println("Thread $i locked $path")

                            client.withKvClient { kvClient ->
                                println("Thread $i assigning $keyval")
                                kvClient.putValue(path, keyval)

                                if (kvClient.getValue(path)?.asString == keyval)
                                    println("Thread $i is the leader")

                                // delete the key
                                //kvClient.delete(key)

                                println("Thread $i is waiting")
                                sleep(15.seconds)
                                println("Thread $i is done waiting")
                            }

                            println("Thread $i is unlocking")
                            lock.unlock(path)
                            println("Thread $i is done unlocking")
                        }
                    }
                }
            countdown.countDown()
        }
    }
    countdown.await()
}
