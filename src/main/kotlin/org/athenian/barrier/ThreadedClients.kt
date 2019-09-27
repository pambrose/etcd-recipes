package org.athenian.barrier

import org.athenian.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val barrierName = "barrier1"
    val cnt = 5
    val latch = CountDownLatch(cnt)

    DistributedBarrier.reset(url, barrierName)

    thread {
        val barrier = DistributedBarrier(url, barrierName)
        sleep(5.seconds)
        barrier.setBarrier()
        println("Set Barrier")
        sleep(10.seconds)
        barrier.removeBarrier()
        println("Removed Barrier")
    }



    repeat(5) {
        thread {
            val barrier = DistributedBarrier(url, barrierName)
            sleep(6.seconds)
            println("Waiting on Barrier")
            barrier.waitOnBarrier()
            println("Done Waiting on Barrier")

        }
    }

}