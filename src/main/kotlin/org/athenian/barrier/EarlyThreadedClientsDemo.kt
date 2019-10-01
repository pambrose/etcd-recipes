package org.athenian.barrier

import org.athenian.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val barrierName = "/barriers/earlythreadedclients"
    val count = 5
    val waitLatch = CountDownLatch(count)
    val goLatch = CountDownLatch(1)

    DistributedBarrier.reset(url, barrierName)

    repeat(count) { id ->
        thread {
            DistributedBarrier(url, barrierName)
                .use { barrier ->
                    println("$id Waiting on Barrier")
                    barrier.waitOnBarrier(1.seconds)

                    println("$id Timedout waiting on barrier, waiting again")
                    barrier.waitOnBarrier()

                    println("$id Done Waiting on Barrier")
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