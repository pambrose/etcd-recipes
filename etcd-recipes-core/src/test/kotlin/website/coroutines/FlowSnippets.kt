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

package website.coroutines

import io.etcd.jetcd.Client
import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.keyAsString
import io.etcd.recipes.common.valueAsString
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.coroutines.LeadershipEvent
import io.etcd.recipes.coroutines.WatchFlowEvent
import io.etcd.recipes.coroutines.backgroundExceptionsAsFlow
import io.etcd.recipes.coroutines.connectionStateAsFlow
import io.etcd.recipes.coroutines.eventsAsFlow
import io.etcd.recipes.coroutines.leadershipAsFlow
import io.etcd.recipes.coroutines.leaseEventsAsFlow
import io.etcd.recipes.coroutines.lockLostAsFlow
import io.etcd.recipes.coroutines.permitLostAsFlow
import io.etcd.recipes.coroutines.recoveryEventsAsFlow
import io.etcd.recipes.coroutines.watchAsFlow
import io.etcd.recipes.coroutines.watchEventsAsFlow
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedSemaphore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

suspend fun watchFlow(client: Client) {
  // --8<-- [start:watch-flow]
  client
    .watchAsFlow("/config", watchOption { isPrefix(true) })
    .collect { element ->
      when (element) {
        is WatchFlowEvent.Response -> {
          element.response.events.forEach { event ->
            logger.info { "${event.eventType} ${event.keyAsString} = ${event.valueAsString}" }
          }
        }

        // Recovery arrives in-band and in order, so a collector keeping derived
        // state learns about a gap before the events that follow it.
        is WatchFlowEvent.Recovery -> {
          logger.warn { "Watch recovery: ${element.event}" }
        }
      }
    }
  // --8<-- [end:watch-flow]
}

suspend fun watchFlowResync(client: Client) {
  // --8<-- [start:watch-flow-resync]
  // resyncWith runs after a compaction: re-read your world and return the revision
  // to re-anchor at. Events between the compaction point and the anchor are gone —
  // this hook is your only chance to reconcile.
  val resyncWith = {
    val response = client.getResponse("/config", getOption { isPrefix(true) })
    response.kvs.forEach { kv -> logger.info { "Resync: ${kv.key.asString}" } }
    response.header.revision + 1
  }

  client
    .watchAsFlow("/config", watchOption { isPrefix(true) }, resyncWith = resyncWith)
    .collect { element ->
      if (element is WatchFlowEvent.Recovery && element.event is WatchRecoveryEvent.Resynced)
        logger.warn { "Reconciled after a compaction" }
    }
  // --8<-- [end:watch-flow-resync]
}

suspend fun watchEventsFlow(client: Client) {
  // --8<-- [start:watch-events-flow]
  // Flattens responses into events and drops recovery transitions. Fine for a
  // stateless collector; wrong for one that keeps derived state.
  client
    .watchEventsAsFlow("/config", watchOption { isPrefix(true) })
    .collect { event ->
      logger.info { "${event.eventType} ${event.keyAsString}" }
    }
  // --8<-- [end:watch-events-flow]
}

suspend fun boundedWatchFlow(client: Client) {
  // --8<-- [start:capacity]
  // A bounded capacity opts INTO backpressure: once 64 elements are pending, the
  // watcher's own dispatcher thread parks — which also stalls recovery and resync
  // for this watch. Only bound it when a stalled watch beats an unbounded buffer.
  client
    .watchEventsAsFlow("/config", watchOption { isPrefix(true) }, capacity = 64)
    .collect { event -> logger.info { "${event.eventType} ${event.keyAsString}" } }
  // --8<-- [end:capacity]
}

suspend fun leadershipFlow(client: Client) {
  // --8<-- [start:leadership-flow]
  // Observing, not participating: the current leader is emitted immediately on
  // collect, so a late subscriber is never left blind waiting for a hand-off.
  client
    .leadershipAsFlow("/elections/orders")
    .collect { event ->
      when (event) {
        is LeadershipEvent.Elected -> logger.info { "Leader is ${event.leaderName}" }
        is LeadershipEvent.Vacated -> logger.warn { "No leader right now" }
        is LeadershipEvent.WatchFailed -> logger.error(event.cause) { "Observation stopped" }
      }
    }
  // --8<-- [end:leadership-flow]
}

fun cacheFlow(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:cache-flow]
  PathChildrenCache(client, "/services").use { cache ->
    // Subscribe BEFORE starting the cache: events fired while nothing collects are
    // not buffered, so a late collector misses INITIALIZED.
    scope.launch {
      cache.eventsAsFlow().collect { event ->
        logger.info { "${event.type} ${event.childName}" }
      }
    }
    scope.launch {
      cache.recoveryEventsAsFlow().collect { event ->
        logger.warn { "Cache watch recovery: $event" }
      }
    }
    cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT)
  }
  // --8<-- [end:cache-flow]
}

fun nodeCacheFlow(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:node-cache-flow]
  NodeCache(client, "/config/mode", StringCodec).use { cache ->
    scope.launch {
      cache.eventsAsFlow().collect { event ->
        logger.info { "${event.type}: ${event.value}" }
      }
    }
    cache.start()
  }
  // --8<-- [end:node-cache-flow]
}

fun discoveryFlow(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:discovery-flow]
  ServiceCache(client, "/services/orders/names", "worker").use { cache ->
    scope.launch {
      cache.eventsAsFlow().collect { event ->
        val verb = if (event.isAdd) "joined" else "left"
        logger.info { "${event.serviceInstance?.id} $verb ${event.serviceName}" }
      }
    }
    cache.start()
  }
  // --8<-- [end:discovery-flow]
}

fun lockLostFlow(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:lock-lost-flow]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    scope.launch {
      // Unbuffered by design: the listener fires on jetcd's lease-callback thread,
      // which must never block, so this flow takes no capacity argument.
      mutex.lockLostAsFlow().collect { event ->
        logger.error(event.cause) { "Lost the lock; abandoning work" }
      }
    }
  }

  DistributedSemaphore(client, "/semaphores/uploads", permits = 3).use { semaphore ->
    scope.launch {
      semaphore.permitLostAsFlow().collect { event ->
        logger.error(event.cause) { "Lost a permit" }
      }
    }
  }
  // --8<-- [end:lock-lost-flow]
}

fun leaseFlow(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:lease-flow]
  TransientKeyValue(client, "/nodes/worker-1", "alive").use { kv ->
    scope.launch {
      kv.leaseEventsAsFlow().collect { event ->
        when (event) {
          is LeaseEvent.Suspended -> logger.warn { "Keep-alive faltering: ${event.cause}" }
          is LeaseEvent.Expired -> logger.error { "Key ownership was lost" }
          is LeaseEvent.Restored -> logger.info { "Healed onto lease ${event.newLeaseId}" }
          is LeaseEvent.Failed -> logger.error(event.cause) { "Healing abandoned" }
        }
      }
    }
  }
  // --8<-- [end:lease-flow]
}

fun connectionAndExceptionFlows(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:connection-flow]
  PathChildrenCache(client, "/services").use { cache ->
    scope.launch {
      // Emits the current state on collect, then every transition. Conflated: a slow
      // collector sees the latest state rather than every intermediate one.
      cache.connectionStateAsFlow().collect { state ->
        if (state == ConnectionState.LOST) logger.error { "Cache is no longer trustworthy" }
      }
    }
    scope.launch {
      // The push counterpart to the pull-only `exceptions` list.
      cache.backgroundExceptionsAsFlow().collect { failure ->
        logger.error(failure.throwable) { "Background failure in ${failure.context}" }
      }
    }
  }
  // --8<-- [end:connection-flow]
}

fun composedFlows(
  scope: CoroutineScope,
  client: Client,
) {
  // --8<-- [start:composed]
  PathChildrenCache(client, "/services").use { cache ->
    // One parent job per subscription set: cancelling `collectors` unsubscribes
    // every flow — each awaitClose removes its listener or closes its watcher.
    val collectors =
      scope.launch {
        launch {
          cache.eventsAsFlow().collect { event ->
            logger.info { "Cache: ${event.type} ${event.childName}" }
          }
        }
        launch {
          cache.connectionStateAsFlow().collect { state ->
            logger.info { "Connection state: $state" }
          }
        }
        launch {
          client.leadershipAsFlow("/elections/orders").collect { event ->
            logger.info { "Leadership: $event" }
          }
        }
      }

    cache.start(buildInitial = true)
    collectors.cancel()
  }
  // --8<-- [end:composed]
}
