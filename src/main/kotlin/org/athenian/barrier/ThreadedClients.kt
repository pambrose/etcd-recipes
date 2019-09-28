package org.athenian.barrier

import org.athenian.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val barrierName = "barrier3"
    val cnt = 5
    val waitLatch = CountDownLatch(cnt)
    val goLatch = CountDownLatch(1)

    DistributedBarrier.reset(url, barrierName)

    thread {
        DistributedBarrier(url, barrierName)
            .use { barrier ->
                println("Setting Barrier")
                barrier.setBarrier()
                goLatch.countDown()
                sleep(6.seconds)
                println("Removing Barrier")
                barrier.removeBarrier()
                sleep(3.seconds)
            }
    }

    repeat(cnt) {
        thread {
            goLatch.await()
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
    }

    waitLatch.await()
    println("Done")
}