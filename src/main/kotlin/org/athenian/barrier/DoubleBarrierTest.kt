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
    val barrierName = "/barriers/doublebarriertest"
    val count = 5
    val enterLatch = CountDownLatch(count - 1)
    val leaveLatch = CountDownLatch(count - 1)
    val doneLatch = CountDownLatch(count)

    DistributedDoubleBarrier.reset(url, barrierName)

    fun enterBarrier(id: Int, barrier: DistributedDoubleBarrier, retryCount: Int = 0) {
        sleep(10.random.seconds)

        repeat(retryCount) {
            println("#$id Waiting to enter barrier")
            barrier.enter(2.seconds)
            println("#$id Timed out entering barrier")
        }

        enterLatch.countDown()
        println("#$id Waiting to enter barrier")
        barrier.enter()
        println("#$id Entered barrier")
    }

    fun leaveBarrier(id: Int, barrier: DistributedDoubleBarrier, retryCount: Int = 0) {
        sleep(10.random.seconds)

        repeat(retryCount) {
            println("#$id Waiting to leave barrier")
            barrier.leave(2.seconds)
            println("#$id Timed out leaving barrier")
        }

        leaveLatch.countDown()
        println("#$id Waiting to leave barrier")
        barrier.leave()
        println("#$id Left barrier")

        doneLatch.countDown()
    }

    repeat(count - 1) { id ->
        thread {
            DistributedDoubleBarrier(url, barrierName, count)
                .use { barrier ->
                    enterBarrier(id, barrier, 2)
                    sleep(5.random.seconds)
                    leaveBarrier(id, barrier, 2)
                }
        }
    }

    DistributedDoubleBarrier(url, barrierName, count)
        .use { barrier ->
            enterLatch.await()
            sleep(2.seconds)
            enterBarrier(99, barrier)

            leaveLatch.await()
            sleep(2.seconds)
            leaveBarrier(99, barrier)
        }

    doneLatch.await()

    println("Done")
}