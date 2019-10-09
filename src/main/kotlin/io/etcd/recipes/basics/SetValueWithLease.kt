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

import com.sudothought.common.util.repeatWithSleep
import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.recipes.common.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/foo"
    val keyval = "foobar"
    val countdown = CountDownLatch(2)

    thread {
        try {
            sleep(3.seconds)

            Client.builder().endpoints(*urls.toTypedArray()).build()
                .use { client ->
                    client.withLeaseClient { leaseClient ->
                        client.withKvClient { kvClient ->
                            println("Assigning $path = $keyval")
                            val lease = leaseClient.grant(5).get()
                            kvClient.putValue(path, keyval, lease.asPutOption)
                        }
                    }
                }
        } finally {
            countdown.countDown()
        }
    }

    thread {
        try {
            Client.builder().endpoints(*urls.toTypedArray()).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        repeatWithSleep(12) { _, start ->
                            val kval = kvClient.getValue(path, "unset")
                            println("Key $path = $kval after ${System.currentTimeMillis() - start}ms")
                        }
                    }
                }

        } finally {
            countdown.countDown()
        }
    }

    countdown.await()
}