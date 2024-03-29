/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.examples.counter

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.counter.DistributedAtomicLong
import kotlin.time.measureTimedValue

fun main() {
  val urls = listOf("http://localhost:2379")
  val counterPath = "counter2"

  connectToEtcd(urls) { client ->

    DistributedAtomicLong.delete(client, counterPath)

    val counters = List(30) { DistributedAtomicLong(client, counterPath) }

    val (total, dur) =
      measureTimedValue {
        val count = 25
        counters
          .onEach { counter ->
            repeat(count) { counter.increment() }
            repeat(count) { counter.decrement() }
            repeat(count) { counter.add(5) }
            repeat(count) { counter.subtract(5) }
          }
          .first()
          .get()
      }

    println("Total: $total in $dur")

    counters.forEach { it.close() }
  }
}