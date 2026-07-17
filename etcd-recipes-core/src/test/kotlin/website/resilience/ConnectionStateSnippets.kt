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

package website.resilience

import io.etcd.jetcd.Client
import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.ping
import io.etcd.recipes.coroutines.connectionStateAsFlow
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.lock.DistributedMutex
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun connectionStateListener(client: Client) {
  // --8<-- [start:state-listener]
  TransientKeyValue(client, "/services/api", "up", leaseTtlSecs = 5L).use { tkv ->
    tkv.addConnectionStateListener { newState, previous ->
      logger.info { "Connection state: $previous -> $newState" }
      when (newState) {
        // A stream errored; recovery is in progress. Nothing is lost yet.
        ConnectionState.SUSPENDED -> logger.warn { "Degraded; holding off on new work" }

        // The stream — and, for a lease, the ownership it backs — is re-established.
        ConnectionState.RECONNECTED -> logger.info { "Back; safe to resume" }

        // A lease expired or recovery was abandoned. Ownership may be someone else's.
        ConnectionState.LOST -> logger.error { "Ownership may be gone; stop acting on it" }

        ConnectionState.CONNECTED -> logger.info { "Initial state" }
      }
    }
  }
  // --8<-- [end:state-listener]
}

fun healthEndpoint(client: Client) {
  // --8<-- [start:health-check]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    // Passive: derived from events the recipe already observed. No RPC, so it is
    // cheap enough to call on every scrape of a liveness endpoint.
    val live = mutex.isHealthy()

    // Active: a bounded, count-only GET through the usual retry/timeout funnel.
    // Costs a round trip, so save it for a readiness probe.
    val ready = mutex.ping()

    logger.info { "live=$live ready=$ready state=${mutex.connectionState}" }
  }
  // --8<-- [end:health-check]
}

fun clientPing(client: Client) {
  // --8<-- [start:client-ping]
  // The same probe without a recipe: useful for a readiness check that owns a
  // Client but no recipe yet. A tight per-probe deadline keeps the endpoint honest.
  val probe = RpcResilience(RetryPolicy.never, operationTimeout = 2.seconds)
  logger.info { "etcd reachable: ${client.ping(probe)}" }
  // --8<-- [end:client-ping]
}

suspend fun connectionStateFlow(client: Client) {
  // --8<-- [start:state-flow]
  TransientKeyValue(client, "/services/api", "up").use { tkv ->
    // Emits the current state immediately, then every transition. Conflated, so a
    // slow collector sees the latest state rather than every intermediate one.
    tkv.connectionStateAsFlow().collect { state ->
      logger.info { "state=$state" }
    }
  }
  // --8<-- [end:state-flow]
}
