package org.athenian.counter

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val count = 10
    val latch = CountDownLatch(count)
    val counterName = "counter2"

    DistributedAtomicLong.resetCounter(url, counterName)

    val counters = List(count) {
        thread {
            DistributedAtomicLong(url, counterName)
                .use { counter ->
                    val cnt = 5
                    repeat(cnt) { counter.increment() }
                    repeat(cnt) { counter.decrement() }
                    repeat(cnt) { counter.add(5) }
                    repeat(cnt) { counter.subtract(5) }
                }
            latch.countDown()
        }
    }

    latch.await()
    DistributedAtomicLong(url, counterName)
        .use { counter ->
            println("Total: ${counter.get()}")
        }
}