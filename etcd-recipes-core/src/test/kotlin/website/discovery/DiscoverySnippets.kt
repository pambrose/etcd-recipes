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

package website.discovery

import io.etcd.jetcd.Client
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.jsonCodec
import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.discovery.ServiceRegistry
import io.etcd.recipes.discovery.ServiceType
import io.etcd.recipes.discovery.payload
import io.etcd.recipes.discovery.serviceInstance
import io.etcd.recipes.discovery.setPayload
import io.etcd.recipes.discovery.withServiceDiscovery
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class WorkerMeta(
  val region: String,
  val weight: Int,
)

fun registerInstance(client: Client) {
  // --8<-- [start:register]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    val instance =
      serviceInstance("worker", """{"weight":5}""") {
        apply {
          address = "10.0.0.7"
          port = 8080
        }
      }

    // Writes /services/orders/names/worker/<id>, bound to a self-healing lease.
    discovery.registerService(instance)

    // Mutate the instance, then push the new JSON to the same key.
    instance.port = 8081
    discovery.updateService(instance)

    // Removes the key now rather than leaving it to expire at the TTL.
    discovery.unregisterService(instance)
  }
  // --8<-- [end:register]
}

fun buildInstance() {
  // --8<-- [start:builder]
  val instance =
    serviceInstance("worker", """{"weight":5}""") {
      apply {
        address = "10.0.0.7"
        port = 8080
        sslPort = 8443
        uri = "https://10.0.0.7:8443"
        serviceType = ServiceType.DYNAMIC
        enabled = true
      }
    }

  // id is random and assigned at construction. It is the last segment of the
  // instance's etcd key, and what queryForInstance(name, id) takes.
  logger.info { "${instance.name}/${instance.id} isDynamic=${instance.serviceType.isDynamic}" }

  // toJson()/toObject() are the wire format the recipes store and read.
  val parsed: ServiceInstance = ServiceInstance.toObject(instance.toJson())
  logger.info { "Round-tripped ${parsed.name} on port ${parsed.port}" }
  // --8<-- [end:builder]
}

fun typedPayload() {
  // --8<-- [start:typed]
  // A UTF-8 text codec is required: jsonPayload is a String, so a binary codec
  // cannot round-trip through it.
  val codec = jsonCodec<WorkerMeta>()

  val instance =
    serviceInstance("worker", WorkerMeta("us-east", 5), codec) {
      apply { port = 8080 }
    }

  // Decode it back; the ServiceInstance wire format is unchanged by typing.
  val meta: WorkerMeta = instance.payload(codec)
  logger.info { "Registered in ${meta.region} with weight ${meta.weight}" }

  // Re-encode a new value into jsonPayload in place, then updateService().
  instance.setPayload(WorkerMeta("us-east", 9), codec)
  // --8<-- [end:typed]
}

fun queryInstances(client: Client) {
  // --8<-- [start:query]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    // One-shot reads: every call is a range GET, not a cached lookup.
    val names: List<String> = discovery.queryForNames()
    val instances: List<ServiceInstance> = discovery.queryForInstances("worker")

    logger.info { "Services: $names, worker instances: ${instances.size}" }

    // Throws EtcdRecipeException if that exact instance key is gone.
    instances.firstOrNull()?.let { first ->
      val one = discovery.queryForInstance("worker", first.id)
      logger.info { "Found ${one.name}/${one.id}" }
    }
  }
  // --8<-- [end:query]
}

fun watchCache(client: Client) {
  // --8<-- [start:cache]
  ServiceDiscovery(client, "/services/orders").use { discovery ->
    discovery.withServiceCache("worker") {
      // Register listeners BEFORE start(): the snapshot taken by start() does not
      // replay through them, and instances registered after it do.
      addListenerForChanges { eventType, isAdd, serviceName, serviceInstance ->
        logger.info { "$eventType isAdd=$isAdd $serviceName -> $serviceInstance" }
      }

      // One-shot: a second start() throws.
      start()

      // In-memory read of the watch-maintained set; no round trip.
      logger.info { "${instances.size} instances of worker" }
    }
  }
  // --8<-- [end:cache]
}

fun standaloneCache(client: Client) {
  // --8<-- [start:standalone-cache]
  // Read-only side, no registry: note the path is <servicePath>/names, which is
  // what ServiceDiscovery hands its caches internally.
  ServiceCache(client, "/services/orders/names", "worker").use { cache ->
    cache.addRecoveryListener { event ->
      when (event) {
        is WatchRecoveryEvent.Resynced -> logger.info { "Watch resynced after compaction" }
        is WatchRecoveryEvent.Failed -> logger.error { "Watch abandoned; instances are stale" }
        else -> logger.info { "Watch recovery: $event" }
      }
    }
    cache.start()
    logger.info { "${cache.instances.size} instances" }
  }
  // --8<-- [end:standalone-cache]
}

fun registryOnly(client: Client) {
  // --8<-- [start:registry]
  // Write side only. Registration leases self-heal: if one expires, the healer
  // grants a new lease and re-runs the registration CAS.
  ServiceRegistry(client, "/services/orders", leaseTtlSecs = 5L).use { registry ->
    registry.addLeaseListener { event ->
      when (event) {
        is LeaseEvent.Expired -> logger.warn { "Registration lease expired; healing" }
        is LeaseEvent.Restored -> logger.info { "Re-registered under lease ${event.newLeaseId}" }
        is LeaseEvent.Failed -> logger.error { "Healing abandoned; this instance is unregistered" }
        is LeaseEvent.Suspended -> logger.warn { "Keep-alive stream is retrying" }
      }
    }

    val instance = serviceInstance("worker", """{"weight":5}""")
    registry.registerService(instance)
  }
  // --8<-- [end:registry]
}

fun scopedDiscovery(client: Client) {
  // --8<-- [start:scoped]
  // Closes the façade — and every cache and provider it handed out — on exit.
  withServiceDiscovery(client, "/services/orders") {
    val instance = serviceInstance("worker", """{"weight":5}""")
    registerService(instance)
    logger.info { "Names: ${queryForNames()}" }
  }
  // --8<-- [end:scoped]
}
