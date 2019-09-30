package org.athenian.counter

import org.athenian.random
import org.athenian.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlin.time.milliseconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val counterName = "counter2"
    val count = 10
    val outerLatch = CountDownLatch(count)

    DistributedAtomicLong.reset(url, counterName)


    val (_, dur) =
        measureTimedValue {
            repeat(count) {
                thread {
                    DistributedAtomicLong(url, counterName)
                        .use { counter ->
                            val innerLatch = CountDownLatch(4)
                            val cnt = 50
                            val maxPause = 50

                            thread {
                                repeat(cnt) { counter.increment() }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
                            }

                            thread {
                                repeat(cnt) { counter.decrement() }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
                            }

                            thread {
                                repeat(cnt) { counter.add(5) }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
                            }

                            thread {
                                repeat(cnt) { counter.subtract(5) }
                                sleep(maxPause.random.milliseconds)
                                innerLatch.countDown()
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
            println("Total: ${counter.get()} in $dur")
        }
}