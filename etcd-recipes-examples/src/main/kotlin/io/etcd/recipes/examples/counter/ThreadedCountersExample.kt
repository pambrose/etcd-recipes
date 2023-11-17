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

import com.github.pambrose.common.concurrent.thread
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.counter.withDistributedAtomicLong
import java.util.concurrent.CountDownLatch
import kotlin.time.measureTimedValue

fun main() {
  val urls = listOf("http://localhost:2379")
  val path = "/counters"
  val threadCount = 5
  val repeatCount = 10
  val latch = CountDownLatch(threadCount)

  connectToEtcd(urls) { client ->

    DistributedAtomicLong.delete(client, path)

    val (_, dur) =
      measureTimedValue {
        repeat(threadCount) { i ->
          thread(latch) {
            println("Creating counter #$i")
            withDistributedAtomicLong(client, path) {
              repeat(repeatCount) { increment() }
              repeat(repeatCount) { decrement() }
              repeat(repeatCount) { add(5) }
              repeat(repeatCount) { subtract(5) }
            }
          }
        }
        latch.await()
      }

    withDistributedAtomicLong(client, path) { println("Counter value = ${get()} in $dur") }
  }
}
