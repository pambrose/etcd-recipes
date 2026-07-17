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

package website.observability

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdMetrics
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.coroutines.backgroundExceptionsAsFlow
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.lock.DistributedMutex
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

// --8<-- [start:custom-metrics]
// Every EtcdMetrics method has a no-op default body, so an implementation overrides
// only the seams it cares about. Implementations must be thread-safe and cheap: they
// run on RPC-caller threads, the watch dispatcher, and the lease healer.
class CountingEtcdMetrics : EtcdMetrics {
  val retries = AtomicLong(0L)
  val leaseHeals = AtomicLong(0L)

  override fun recordRpc(
    opName: String,
    duration: Duration,
    attempts: Int,
    failed: Boolean,
  ) {
    if (attempts > 1) retries.fetchAndAdd((attempts - 1).toLong())
    if (failed) logger.warn { "$opName failed after $attempts attempt(s) in $duration" }
  }

  override fun incrementKeepAlive(
    kind: String,
    leaseId: Long,
  ) {
    // kind is one of renewal / suspended / expired / restored / failed.
    if (kind == "restored") leaseHeals.incrementAndFetch()
  }

  override fun incrementWatchRecovery(
    kind: String,
    key: String,
  ) {
    logger.info { "Watch recovery on $key: $kind" }
  }
}
// --8<-- [end:custom-metrics]

fun installMetrics(client: Client) {
  // --8<-- [start:install-metrics]
  // withMetrics routes the RPC, watch, and lease funnels — plus every recipe seam —
  // to one sink. Pass the result wherever a recipe takes `resilience`.
  val metrics = CountingEtcdMetrics()
  val resilience = ResilienceConfig.DEFAULT.withMetrics(metrics)

  DistributedMutex(client, "/locks/orders", resilience = resilience).use { mutex ->
    logger.info { "isLocked=${mutex.isLocked} retriesSoFar=${metrics.retries.load()}" }
  }
  // --8<-- [end:install-metrics]
}

fun noOpMetrics() {
  // --8<-- [start:no-op-metrics]
  // The default. Nothing is recorded and callers pay nothing when metrics are off.
  val resilience = ResilienceConfig.DEFAULT
  logger.info { "metrics is NoOp: ${resilience.metrics === EtcdMetrics.NoOp}" }
  // --8<-- [end:no-op-metrics]
}

fun backgroundExceptions(client: Client) {
  // --8<-- [start:background-exceptions]
  TransientKeyValue(client, "/services/api", "up").use { tkv ->
    // Push: fires on the recipe's own thread as each background failure happens.
    // `context` is the recipe's identity, e.g. "TransientKeyValue[/services/api]",
    // so one handler shared across recipes can still attribute the failure.
    tkv.addBackgroundExceptionListener { context, throwable ->
      logger.error(throwable) { "Background failure in $context" }
    }

    // Pull: the same failures accumulate here for a caller that would rather poll.
    if (tkv.hasExceptions) {
      tkv.exceptions.forEach { e -> logger.warn(e) { "Recorded earlier" } }
      tkv.clearExceptions()
    }
  }
  // --8<-- [end:background-exceptions]
}

suspend fun backgroundExceptionFlow(client: Client) {
  // --8<-- [start:exceptions-flow]
  TransientKeyValue(client, "/services/api", "up").use { tkv ->
    tkv.backgroundExceptionsAsFlow().collect { failure ->
      logger.error(failure.throwable) { "[${failure.context}] background failure" }
    }
  }
  // --8<-- [end:exceptions-flow]
}
