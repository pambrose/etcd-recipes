package org.athenian.counter

import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"

    DistributedAtomicLong.reset(url, "counter2")

    val counters = List(30) { DistributedAtomicLong(url, "counter2") }

    val total1 =
        counters
            .onEach { counter ->
                repeat(25) { counter.increment() }
                repeat(25) { counter.decrement() }
                repeat(25) { counter.add(5) }
                repeat(25) { counter.subtract(5) }
            }
            .map { counter -> counter.get() }
            .sum()
    println("Total: $total1")

    val total2 =
        counters
            .onEach { counter -> repeat(25) { counter.increment() } }
            .onEach { counter -> repeat(25) { counter.decrement() } }
            .onEach { counter -> repeat(25) { counter.add(5) } }
            .onEach { counter -> repeat(25) { counter.subtract(5) } }
            .map { counter -> counter.get() }
            .sum()
    println("Total: $total2")

    counters.forEach { it.close() }

}