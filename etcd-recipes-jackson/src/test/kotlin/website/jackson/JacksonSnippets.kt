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

package website.jackson

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.etcd.jetcd.Client
import io.etcd.recipes.cache.TypedPathChildrenCache
import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.putValue
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.payload
import io.etcd.recipes.discovery.serviceInstance
import io.etcd.recipes.jackson.JacksonCodec
import io.etcd.recipes.jackson.jacksonCodec
import io.etcd.recipes.queue.TypedDistributedQueue
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

// --8<-- [start:pojo]
// @JsonCreator keeps this data class constructable by a *vanilla* ObjectMapper, exactly
// as a Java bean would be. Without it, Kotlin data classes need jackson-module-kotlin.
data class Order
  @JsonCreator
  constructor(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("qty") val qty: Int,
  )
// --8<-- [end:pojo]

fun basicCodec() {
  // --8<-- [start:codec]
  // A Class token, the Java-facing form.
  val fromClass: EtcdCodec<Order> = JacksonCodec(Order::class.java)

  // Or the reified Kotlin helper, which builds the TypeReference for you.
  val reified: EtcdCodec<Order> = jacksonCodec<Order>()
  // --8<-- [end:codec]
  logger.info { "$fromClass $reified" }
}

fun genericCodec() {
  // --8<-- [start:generic]
  // A Class token cannot express List<Order> — its type argument is erased. Use a
  // TypeReference (or the reified helper, which makes one internally) instead.
  val explicit: EtcdCodec<List<Order>> = JacksonCodec(object : TypeReference<List<Order>>() {})
  val reified: EtcdCodec<List<Order>> = jacksonCodec<List<Order>>()
  // --8<-- [end:generic]
  logger.info { "$explicit $reified" }
}

fun kotlinMapper() {
  // --8<-- [start:kotlin-mapper]
  // The default ObjectMapper is vanilla. findAndRegisterModules() ServiceLoader-discovers
  // jackson-module-kotlin when it is on the classpath, which is what makes plain Kotlin
  // data classes (no no-arg constructor, no @JsonCreator) decodable.
  val mapper = ObjectMapper().findAndRegisterModules()
  val codec: EtcdCodec<Order> = jacksonCodec<Order>(mapper)
  // --8<-- [end:kotlin-mapper]
  logger.info { "$codec" }
}

fun typedKeyValue(client: Client) {
  // --8<-- [start:typed-kv]
  val codec = jacksonCodec<Order>()

  client.putValue("/config/order", Order("A-1", 3), codec)
  val order: Order? = client.getValue("/config/order", codec)
  logger.info { "Read back $order" }
  // --8<-- [end:typed-kv]
}

fun typedQueue(client: Client) {
  // --8<-- [start:typed-queue]
  // A JacksonCodec goes anywhere an EtcdCodec goes — nothing in core knows it is Jackson.
  TypedDistributedQueue(client, "/queues/orders", jacksonCodec<Order>()).use { queue ->
    queue.enqueue(Order("A-1", 3))
    val order: Order = queue.dequeue()
    logger.info { "Dequeued $order" }
  }
  // --8<-- [end:typed-queue]
}

fun typedCache(client: Client) {
  // --8<-- [start:typed-cache]
  TypedPathChildrenCache(client, "/config/orders", jacksonCodec<Order>()).use { cache ->
    cache.addListener { event -> logger.info { "${event.childName} -> ${event.data}" } }
    cache.start(buildInitial = true)

    cache.currentData.forEach { child -> logger.info { "${child.key} = ${child.value}" } }
  }
  // --8<-- [end:typed-cache]
}

fun typedServicePayload(client: Client) {
  // --8<-- [start:typed-service]
  // Service payloads ride in the instance's `jsonPayload` String, so the codec must emit
  // UTF-8 text. JacksonCodec emits JSON, which qualifies.
  val codec = jacksonCodec<Order>()
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
