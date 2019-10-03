package org.athenian.barrier

import com.sudothought.common.util.random
import com.sudothought.common.util.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val barrierName = "/barriers/barrierwithcountdemo"
    val count = 30
    val waitLatch = CountDownLatch(count)
    val retryLatch = CountDownLatch(count - 1)

    DistributedBarrierWithCount.reset(url, barrierName)

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
            DistributedBarrierWithCount(url, barrierName, count)
                .use { barrier ->
                    waiter(i, barrier, 5)
                }
        }
    }

    retryLatch.await()
    sleep(2.seconds)

    DistributedBarrierWithCount(url, barrierName, count)
        .use { barrier ->
            waiter(99, barrier)
        }

    waitLatch.await()

    println("Done")
}