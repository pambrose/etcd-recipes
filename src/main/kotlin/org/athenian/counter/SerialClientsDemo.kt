package org.athenian.counter

import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val counterName = "counter2"

    DistributedAtomicLong.reset(url, counterName)

    val counters = List(30) { DistributedAtomicLong(url, counterName) }

    val (total, dur) =
        measureTimedValue {
            val count = 25
            counters
                .onEach { dal ->
                    repeat(count) { dal.increment() }
                    repeat(count) { dal.decrement() }
                    repeat(count) { dal.add(5) }
                    repeat(count) { dal.subtract(5) }
                }
                .first()
                .get()
        }

    println("Total: $total in $dur")

    counters.forEach { it.close() }

}