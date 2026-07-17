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

package website.codecs

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.cache.TypedPathChildrenCache
import io.etcd.recipes.common.ByteSequenceCodec
import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.common.putValue
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.payload
import io.etcd.recipes.discovery.serviceInstance
import io.etcd.recipes.keyvalue.TypedTransientKeyValue
import io.etcd.recipes.queue.TypedDistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

// --8<-- [start:serializable]
@Serializable
data class Order(
  val id: String,
  val qty: Int,
)
// --8<-- [end:serializable]

// --8<-- [start:custom-codec]
// A codec is two functions and nothing more. Implement EtcdCodec directly when the
// payload is not JSON — a protobuf message, a packed binary struct, a bare Long.
object LongCodec : EtcdCodec<Long> {
  override fun encode(value: Long): ByteSequence = value.toString().asByteSequence

  override fun decode(bytes: ByteSequence): Long = bytes.asString.toLong()
}
// --8<-- [end:custom-codec]

fun builtInCodecs() {
  // --8<-- [start:built-in]
  // The identity codec: the payload already is a ByteSequence, so nothing is marshalled.
  val raw: EtcdCodec<ByteSequence> = ByteSequenceCodec

  // UTF-8 text.
  val text: EtcdCodec<String> = StringCodec

  // kotlinx-serialization JSON for any @Serializable type. The reified factory resolves
  // the serializer at the call site, so you never name it yourself.
  val orders: EtcdCodec<Order> = jsonCodec<Order>()
  // --8<-- [end:built-in]
  logger.info { "$raw $text $orders" }
}

fun typedKeyValue(client: Client) {
  // --8<-- [start:typed-kv]
  val codec = jsonCodec<Order>()

  // Encodes through the codec and composes the raw ByteSequence put, so it inherits
  // that put's retry semantics.
  client.putValue("/config/order", Order("A-1", 3), codec)

  // Returns null when the key is absent — pick the fallback yourself.
  val order: Order? = client.getValue("/config/order", codec)
  logger.info { "Read back $order" }
  // --8<-- [end:typed-kv]
}

fun typedQueue(client: Client) {
  // --8<-- [start:typed-queue]
  TypedDistributedQueue(client, "/queues/orders", jsonCodec<Order>()).use { queue ->
    queue.enqueue(Order("A-1", 3))
    queue.enqueueAll(listOf(Order("A-2", 1), Order("A-3", 7)))

    // dequeue() hands back an Order, not a ByteSequence.
    val order: Order = queue.dequeue()
    logger.info { "Dequeued $order" }
  }
  // --8<-- [end:typed-queue]
}

fun untypedEscapeHatch(client: Client) {
  // --8<-- [start:untyped]
  TypedDistributedQueue(client, "/queues/orders", jsonCodec<Order>()).use { queue ->
    // A decorator is a Closeable, not an EtcdConnector. The whole connector API is
    // reached through `untyped` — the very instance the decorator wraps.
    queue.untyped.addBackgroundExceptionListener { context, e ->
      logger.warn(e) { "Background failure in $context" }
    }

    if (!queue.untyped.isHealthy()) logger.warn { "Connection lost or closed" }

    queue.enqueue(Order("A-1", 3))
    queue.untyped.exceptions.forEach { e -> logger.error(e) { "Recorded" } }
  }
  // --8<-- [end:untyped]
}

fun singleKeyCache(client: Client) {
  // --8<-- [start:node-cache]
  // NodeCache is not a decorator: it is itself an EtcdConnector that happens to take a
  // codec, so `exceptions` / `isHealthy()` sit directly on the instance — no `untyped`.
  NodeCache(client, "/config/order", jsonCodec<Order>()).use { cache ->
    cache.addListener { event -> logger.info { "${event.type} -> ${event.value}" } }
    cache.start()

    // The live value, decoded on read; null while the key is absent.
    val current: Order? = cache.current
    logger.info { "Current $current, healthy=${cache.isHealthy()}" }
  }
  // --8<-- [end:node-cache]
}

fun typedPrefixCache(client: Client) {
  // --8<-- [start:typed-cache]
  TypedPathChildrenCache(client, "/config/orders", jsonCodec<Order>()).use { cache ->
    cache.addListener { event -> logger.info { "${event.childName} -> ${event.data}" } }
    cache.start(buildInitial = true)

    cache.currentData.forEach { child -> logger.info { "${child.key} = ${child.value}" } }
    val one: Order? = cache.getCurrentData("A-1")
    logger.info { "One $one" }
  }
  // --8<-- [end:typed-cache]
}

fun typedTransientValue(client: Client) {
  // --8<-- [start:typed-transient]
  // The published value is a String, so this needs a UTF-8 text codec.
  TypedTransientKeyValue(client, "/nodes/n1", Order("A-1", 3), jsonCodec<Order>()).use { tkv ->
    tkv.addLeaseListener { event -> logger.info { "Lease: $event" } }

    // Read it back through the matching typed getValue.
    val published: Order? = client.getValue("/nodes/n1", jsonCodec<Order>())
    logger.info { "Published $published" }
  }
  // --8<-- [end:typed-transient]
}

fun typedServicePayload(client: Client) {
  // --8<-- [start:typed-service]
  val codec = jsonCodec<Order>()

  // The typed payload layers over the opaque jsonPayload String, so the ServiceInstance
  // wire format is unchanged. That is also why a binary codec is unsupported here.
  val instance = serviceInstance("orders", Order("A-1", 3), codec) { apply { port = 8080 } }

  ServiceDiscovery(client, "/discovery").use { discovery ->
    discovery.registerService(instance)

    discovery.queryForInstances("orders").forEach { found ->
      val order: Order = found.payload(codec)
      logger.info { "${found.name} carries $order" }
    }
  }
  // --8<-- [end:typed-service]
}
