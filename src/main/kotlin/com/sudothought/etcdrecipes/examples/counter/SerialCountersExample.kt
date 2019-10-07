/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package com.sudothought.etcdrecipes.examples.counter

import com.sudothought.etcdrecipes.counter.DistributedAtomicLong
import kotlin.time.measureTimedValue

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