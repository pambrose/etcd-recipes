package org.athenian.barrier

import org.athenian.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val barrierName = "/barriers/barrier2"
    val cnt = 30
    val waitLatch = CountDownLatch(cnt)
    val finishLatch = CountDownLatch(cnt - 1)

    DistributedBarrierWithCount.reset(url, barrierName)

    fun waiter(id: Int, barrier: DistributedBarrierWithCount, timeout: Boolean = false) {
        sleep(Random.nextLong(10).seconds)
        println("Waiting on Barrier #$id")

        repeat(5) {
            if (timeout) {
                barrier.waitOnBarrier(2.seconds)
                println("Timedout Waiting on Barrier #$id")
                println("Waiting again on Barrier #$id")
            }
        }
        finishLatch.countDown()
        println("Waiter count = ${barrier.waiterCount}")
        barrier.waitOnBarrier()
        println("Done Waiting on Barrier #$id")
        waitLatch.countDown()
    }

    repeat(cnt - 1) {
        thread {
            DistributedBarrierWithCount(url, barrierName, cnt)
                .use { barrier ->
                    waiter(it, barrier, true)
                }
        }
    }

    finishLatch.await()
    sleep(2.seconds)

    DistributedBarrierWithCount(url, barrierName, cnt)
        .use { barrier ->
            waiter(99, barrier)
        }

    waitLatch.await()

    println("Done")
}