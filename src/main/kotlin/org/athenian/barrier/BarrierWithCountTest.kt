package org.athenian.barrier

import org.athenian.random
import org.athenian.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val barrierName = "/barriers/barrierwithcountertest"
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

    repeat(count - 1) { id ->
        thread {
            DistributedBarrierWithCount(url, barrierName, count)
                .use { barrier ->
                    waiter(id, barrier, 5)
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