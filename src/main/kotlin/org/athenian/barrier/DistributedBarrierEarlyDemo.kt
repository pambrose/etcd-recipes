/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package org.athenian.barrier

import com.sudothought.common.util.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val barrierName = "/barriers/earlythreadedclients"
    val count = 5
    val waitLatch = CountDownLatch(count)
    val goLatch = CountDownLatch(1)

    DistributedBarrier.reset(url, barrierName)

    repeat(count) { i ->
        thread {
            DistributedBarrier(url, barrierName)
                .use { barrier ->
                    println("$i Waiting on Barrier")
                    barrier.waitOnBarrier(1.seconds)

                    println("$i Timed out waiting on barrier, waiting again")
                    barrier.waitOnBarrier()

                    println("$i Done Waiting on Barrier")
                    waitLatch.countDown()
                }
        }
        goLatch.countDown()
    }

    thread {
        goLatch.await()
        sleep(5.seconds)
        DistributedBarrier(url, barrierName)
            .use { barrier ->
                println("Setting Barrier")
                barrier.setBarrier()
                sleep(6.seconds)
                println("Removing Barrier")
                barrier.removeBarrier()
                sleep(3.seconds)
            }
    }

    waitLatch.await()
    println("Done")
}