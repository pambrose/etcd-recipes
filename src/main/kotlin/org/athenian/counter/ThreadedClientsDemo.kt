package org.athenian.counter

import org.athenian.utils.random
import org.athenian.utils.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlin.time.milliseconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val counterName = "counter2"
    val threadCount = 10
    val outerLatch = CountDownLatch(threadCount)

    DistributedAtomicLong.reset(url, counterName)

    val (_, dur) =
        measureTimedValue {
            repeat(threadCount) { id ->
                thread {
                    println("Creating counter #$id");
                    DistributedAtomicLong(url, counterName)
                        .use { counter ->
                            val innerLatch = CountDownLatch(4)
                            val count = 50
                            val maxPause = 50

                            thread {
                                println("Begin increments for counter #$id")
                                repeat(count) { counter.increment() }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
                                println("Completed increments for counter #$id")
                            }

                            thread {
                                println("Begin decrements for counter #$id")
                                repeat(count) { counter.decrement() }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
                                println("Completed decrements for counter #$id")
                            }

                            thread {
                                println("Begin adds for counter #$id")
                                repeat(count) { counter.add(5) }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
                                println("Completed adds for counter #$id")
                            }

                            thread {
                                println("Begin subtracts for counter #$id")
                                repeat(count) { counter.subtract(5) }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
                                println("Completed subtracts for counter #$id")
                            }

                            innerLatch.await()
                        }

                    outerLatch.countDown()
                }
            }

            outerLatch.await()
        }

    DistributedAtomicLong(url, counterName)
        .use { counter ->
            println("Counter value = ${counter.get()} in $dur")
        }
}