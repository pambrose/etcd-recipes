/*
 * Copyright © 2026 Paul Ambrose
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

package website.counter

import io.etcd.jetcd.Client
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.counter.withDistributedAtomicLong
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun basicCounter(client: Client) {
  // --8<-- [start:basic]
  DistributedAtomicLong(client, "/counters/orders").use { counter ->
    // Optional: start() is invoked automatically on first use. Call it explicitly
    // when you want the create-if-absent round trip to happen at a point you chose,
    // rather than inside whatever your first increment() turns out to be.
    counter.start()

    logger.info { "Current: ${counter.get()}" }
  }
  // --8<-- [end:basic]
}

fun counterMutators(client: Client) {
  // --8<-- [start:mutators]
  DistributedAtomicLong(client, "/counters/orders").use { counter ->
    // Each mutator returns the value THIS call committed, not a re-read. By the time
    // you look at it another client may already have moved the counter past it.
    val afterIncrement: Long = counter.increment()
    val afterDecrement: Long = counter.decrement()
    val afterAdd: Long = counter.add(10L)
    val afterSubtract: Long = counter.subtract(4L)

    logger.info { "$afterIncrement $afterDecrement $afterAdd $afterSubtract" }

    // get() is a plain read: true at some recent revision, possibly stale on the
    // next line. Never branch on it to decide whether a mutation will succeed.
    logger.info { "Now: ${counter.get()}" }
  }
  // --8<-- [end:mutators]
}

fun counterDefault(client: Client) {
  // --8<-- [start:default]
  // The default is written only by whichever client creates the key first, and is
  // ignored by every instance that finds the counter already present.
  DistributedAtomicLong(client, "/counters/seats", default = 100L).use { counter ->
    logger.info { "Seats left: ${counter.decrement()}" }
  }
  // --8<-- [end:default]
}

fun counterDelete(client: Client) {
  // --8<-- [start:delete]
  // Static, and deliberately not an instance method: removing the key out from under
  // live instances is a destructive act, not a step in a counter's lifecycle.
  DistributedAtomicLong.delete(client, "/counters/orders")
  // --8<-- [end:delete]
}

fun counterResilience(client: Client) {
  // --8<-- [start:resilience]
  // withDistributedAtomicLong does not expose resilience; construct directly for that.
  DistributedAtomicLong(
    client = client,
    counterPath = "/counters/orders",
    default = 0L,
    resilience = ResilienceConfig.DEFAULT,
  ).use { counter ->
    logger.info { "Current: ${counter.get()}" }
  }
  // --8<-- [end:resilience]
}

fun scopedCounter(client: Client) {
  // --8<-- [start:scoped]
  val committed: Long = withDistributedAtomicLong(client, "/counters/orders") { increment() }
  logger.info { "Committed: $committed" }
  // --8<-- [end:scoped]
}
