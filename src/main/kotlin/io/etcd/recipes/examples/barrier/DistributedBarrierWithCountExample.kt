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

package io.etcd.recipes.examples.barrier

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import io.etcd.recipes.barrier.DistributedBarrierWithCount
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.etcdExec
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val barrierPath = "/barriers/barrierwithcountdemo"
    val count = 30
    val waitLatch = CountDownLatch(count)
    val retryLatch = CountDownLatch(count - 1)

    etcdExec(urls) { _, kvClient -> kvClient.deleteChildren(barrierPath) }

    fun waiter(id: Int, barrier: DistributedBarrierWithCount, retryCount: Int = 0) {
        sleep(10.random.seconds)
        println("#$id Waiting on barrier")

        repeat(retryCount) {
            barrier.waitOnBarrier(2.seconds)
            println("#$id Timed out waiting on barrier, waiting again")
        }

        retryLatch.countDown()

        println("#$id Waiter count = ${barrier.waiterCount}")
        barrier.waitOnBarrier()
        println("#$id Done waiting on barrier")
        waitLatch.countDown()
    }

    repeat(count - 1) { i ->
        thread {
            DistributedBarrierWithCount(urls, barrierPath, count)
                .use { barrier ->
                    waiter(i, barrier, 5)
                }
        }
    }

    retryLatch.await()
    sleep(2.seconds)

    DistributedBarrierWithCount(urls, barrierPath, count)
        .use { barrier ->
            waiter(99, barrier)
        }

    waitLatch.await()

    println("Done")
}