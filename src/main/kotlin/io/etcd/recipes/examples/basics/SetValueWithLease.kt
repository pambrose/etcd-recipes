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

package io.etcd.recipes.examples.basics

import com.sudothought.common.concurrent.thread
import com.sudothought.common.util.repeatWithSleep
import com.sudothought.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.grant
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.withKvClient
import io.etcd.recipes.common.withLeaseClient
import java.util.concurrent.CountDownLatch
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/foo"
    val keyval = "foobar"
    val latch = CountDownLatch(2)

    thread(latch) {
        sleep(3.seconds)
        connectToEtcd(urls) { client ->
            client.withKvClient { kvClient ->
                client.withLeaseClient { leaseClient ->
                    println("Assigning $path = $keyval")
                    val lease = leaseClient.grant(5.seconds).get()
                    kvClient.putValue(path, keyval, putOption { withLeaseId(lease.id) })
                }
            }
        }
    }

    thread(latch) {
        etcdExec(urls) { _, kvClient ->
            repeatWithSleep(12) { _, start ->
                val kval = kvClient.getValue(path, "unset")
                println("Key $path = $kval after ${System.currentTimeMillis() - start}ms")
            }
        }
    }

    latch.await()
}