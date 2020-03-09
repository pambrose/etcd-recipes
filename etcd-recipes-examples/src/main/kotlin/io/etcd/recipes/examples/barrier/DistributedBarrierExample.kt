/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.examples.barrier

import com.github.pambrose.common.concurrent.thread
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.barrier.withDistributedBarrier
import io.etcd.recipes.common.connectToEtcd
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val barrierPath = "/barriers/threadedclients"
    val count = 5
    val waitLatch = CountDownLatch(count)
    val goLatch = CountDownLatch(1)

    thread {
        connectToEtcd(urls) { client ->
            withDistributedBarrier(client, barrierPath) {
                println("Setting Barrier")
                setBarrier()
                goLatch.countDown()
                sleep(6.seconds)
                println("Removing Barrier")
                removeBarrier()
                sleep(3.seconds)
            }
        }
    }

    repeat(count) { i ->
        thread(waitLatch) {
            goLatch.await()
            connectToEtcd(urls) { client ->
                withDistributedBarrier(client, barrierPath) {
                    println("$i Waiting on Barrier")
                    waitOnBarrier(1.seconds)

                    println("$i Timed out waiting on barrier, waiting again")
                    waitOnBarrier()

                    println("$i Done Waiting on Barrier")
                }
            }
        }
    }

    waitLatch.await()

    println("Done")
}