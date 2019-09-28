package org.athenian.barrier

import org.athenian.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val barrierName = "barrier2"
    val cnt = 5
    val waitLatch = CountDownLatch(cnt)
    val goLatch = CountDownLatch(1)

    DistributedBarrier.reset(url, barrierName)

    repeat(cnt) {
        thread {
            DistributedBarrier(url, barrierName)
                .use { barrier ->
                    println("Waiting on Barrier #$it")
                    barrier.waitOnBarrier(1.seconds)
                    println("Timedout Waiting on Barrier #$it")
                    println("Waiting again on Barrier #$it")
                    barrier.waitOnBarrier()
                    println("Done Waiting on Barrier #$it")
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