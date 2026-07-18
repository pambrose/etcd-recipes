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

package io.etcd.recipes.examples.basics

import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.LeaseResilience
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates tuning the resilience configuration: watch recovery pacing, lease
 * healing pacing, and RPC retry/timeout behavior — per recipe or per call.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]

  connectToEtcd(urls) { client ->
    // A custom pacing profile for everything a recipe does:
    val resilience =
      ResilienceConfig(
        watch = WatchResilience(RetryPolicy.exponentialBackoff(initialDelay = 100.milliseconds, maxDelay = 5.seconds)),
        lease = LeaseResilience(RetryPolicy.forever),
        rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 8), operationTimeout = 10.seconds),
      )

    PathChildrenCache(client, "/examples/retry-policy", resilience = resilience).use { cache ->
      cache.start(true)
      logger.info { "Cache running with custom resilience: ${cache.currentDataAsMap.size} children" }
    }

    // Or per call: a one-shot read with a tight deadline for a latency-sensitive path
    val oneShot = RpcResilience(RetryPolicy.never, operationTimeout = 2.seconds)
    logger.info { "flag=${client.getValue("/examples/flag", "default", oneShot)}" }

    // And the pre-0.12 behavior, should you want it back:
    val legacy = ResilienceConfig.DISABLED
    logger.info { "legacy config: $legacy" }
  }
}
