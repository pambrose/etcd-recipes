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
    val cnt = 5
    val waitLatch = CountDownLatch(cnt)

    DistributedBarrierWithCount.reset(url, barrierName)

    repeat(cnt) {
        thread {
            DistributedBarrierWithCount(url, barrierName, cnt)
                .use { barrier ->
                    sleep(Random.nextLong(5).seconds)
                    println("Waiting on Barrier #$it")
                    //barrier.waitOnBarrier(1.seconds)
                    //println("Timedout Waiting on Barrier #$it")
                    //println("Waiting again on Barrier #$it")
                    barrier.waitOnBarrier()
                    println("Done Waiting on Barrier #$it")

                    sleep(20.seconds)
                    waitLatch.countDown()
                }
        }
    }

    waitLatch.await()
    println("Done")
}