/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.examples.counter

import io.etcd.recipes.counter.DistributedAtomicLong
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.measureTimedValue

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/counters"
    val threadCount = 5
    val repeatCount = 10
    val latch = CountDownLatch(threadCount)

    DistributedAtomicLong.delete(urls, path)

    val (_, dur) =
        measureTimedValue {
            repeat(threadCount) { i ->
                thread {
                    println("Creating counter #$i")
                    DistributedAtomicLong(urls, path)
                        .use { counter ->
                            repeat(repeatCount) { counter.increment() }
                            repeat(repeatCount) { counter.decrement() }
                            repeat(repeatCount) { counter.add(5) }
                            repeat(repeatCount) { counter.subtract(5) }
                        }
                    latch.countDown()
                }
            }
            latch.await()
        }

    DistributedAtomicLong(urls, path).use { println("Counter value = ${it.get()} in $dur") }
}