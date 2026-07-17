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

package website.keyvalue

import io.etcd.jetcd.Client
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.LeaseResilience
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.keyvalue.TypedTransientKeyValue
import io.etcd.recipes.keyvalue.withTransientKeyValue
import io.etcd.recipes.keyvalue.withTypedTransientKeyValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class NodeInfo(
  val host: String,
  val port: Int,
)

fun basicTransientKeyValue(client: Client) {
  // --8<-- [start:basic]
  // autoStart defaults to true: by the time the constructor returns, the lease has
  // been granted, the key put, and the keep-alive started.
  TransientKeyValue(client, "/nodes/node-1", "10.0.0.7:8080", leaseTtlSecs = 5L).use { kv ->
    // The key exists for exactly as long as this recipe is open and renewing.
    logger.info { "Published ${kv.keyPath} = ${kv.keyValue} as ${kv.clientId}" }
  }
  // close() stops the keep-alive; etcd drops the key when the lease runs out.
  // --8<-- [end:basic]
}

fun deferredStartTransientKeyValue(client: Client) {
  // --8<-- [start:deferred-start]
  // autoStart = false defers every RPC to start(), which is what the rest of the
  // library does. It also makes the recipe constructible without a live etcd.
  TransientKeyValue(
    client = client,
    keyPath = "/nodes/node-1",
    keyValue = "10.0.0.7:8080",
    leaseTtlSecs = 5L,
    autoStart = false,
  ).use { kv ->
    // One-shot: a second start() throws EtcdRecipeRuntimeException.
    kv.start()
    logger.info { "Publishing ${kv.keyPath}" }
  }
  // --8<-- [end:deferred-start]
}

fun transientKeyValueLeaseListener(client: Client) {
  // --8<-- [start:lease-listener]
  TransientKeyValue(
    client = client,
    keyPath = "/nodes/node-1",
    keyValue = "10.0.0.7:8080",
    leaseTtlSecs = 5L,
    autoStart = false,
  ).use { kv ->
    // Unlike a lock or election lease, this one IS healed: on expiry the recipe
    // re-grants a lease and re-puts the key. Publishing your own address has no
    // exclusive ownership for a re-put to race against.
    kv.addLeaseListener { event ->
      when (event) {
        is LeaseEvent.Suspended -> logger.warn { "Renewal hiccup on lease ${event.leaseId}" }

        is LeaseEvent.Expired -> logger.warn { "Lease ${event.leaseId} expired; healing" }

        is LeaseEvent.Restored -> logger.info { "Healed: ${event.oldLeaseId} -> ${event.newLeaseId}" }

        // Healing gave up. The key is gone and stays gone.
        is LeaseEvent.Failed -> logger.error { "Healing abandoned for ${event.leaseId}" }
      }
    }
    // Registering before start() is only possible with autoStart = false.
    kv.start()
  }
  // --8<-- [end:lease-listener]
}

fun transientKeyValueResilience(client: Client) {
  // --8<-- [start:resilience]
  // withTransientKeyValue does not expose resilience; construct directly for that.
  TransientKeyValue(
    client = client,
    keyPath = "/nodes/node-1",
    keyValue = "10.0.0.7:8080",
    leaseTtlSecs = 5L,
    autoStart = false,
    // Healing off: an expired lease now means the key is gone for good, and you
    // find out from a LeaseEvent.Failed rather than a re-put.
    resilience = ResilienceConfig.DEFAULT.copy(lease = LeaseResilience.DISABLED),
  ).use { kv ->
    kv.start()
    logger.info { "Publishing ${kv.keyPath}" }
  }
  // --8<-- [end:resilience]
}

fun scopedTransientKeyValue(client: Client) {
  // --8<-- [start:scoped]
  withTransientKeyValue(client, "/nodes/node-1", "10.0.0.7:8080", leaseTtlSecs = 5L) {
    logger.info { "Registered at $keyPath for the duration of this block" }
  }
  // --8<-- [end:scoped]
}

fun typedTransientKeyValue(client: Client) {
  // --8<-- [start:typed]
  // The value is encoded ONCE, at construction, and published under the lease. It is
  // immutable for the recipe's lifetime, so the codec must produce UTF-8 text.
  TypedTransientKeyValue(
    client = client,
    keyPath = "/nodes/node-1",
    value = NodeInfo("10.0.0.7", 8080),
    codec = jsonCodec<NodeInfo>(),
    leaseTtlSecs = 5L,
  ).use { kv ->
    // A Closeable decorator, not an EtcdConnector: exceptions, isHealthy() and the
    // rest of the connector surface live on `untyped`.
    logger.info { "Healthy: ${kv.untyped.isHealthy()}, published ${kv.untyped.keyValue}" }
  }
  // --8<-- [end:typed]
}

fun scopedTypedTransientKeyValue(client: Client) {
  // --8<-- [start:typed-scoped]
  withTypedTransientKeyValue(client, "/nodes/node-1", NodeInfo("10.0.0.7", 8080), jsonCodec<NodeInfo>()) {
    logger.info { "Registered as ${untyped.keyValue}" }
  }
  // --8<-- [end:typed-scoped]
}
