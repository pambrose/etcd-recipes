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

package io.etcd.recipes.discovery

import com.google.common.collect.Maps.newConcurrentMap
import com.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.DEFAULT_TTL_SECS
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.LeaseListener
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.SelfHealingKeepAlive
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.selfHealingKeepAlive
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Write-side of service discovery: register, update, unregister service instances.
 * Keeps responsibilities separate from the read-side (queries, caches, providers)
 * exposed by [ServiceDiscovery] — letting callers depend only on what they use.
 */
class ServiceRegistry
@JvmOverloads
constructor(
  client: Client,
  val servicePath: String,
  val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  private val namesPath = servicePath.appendToPath("/names")
  private val serviceContextMap: ConcurrentMap<String, ServiceInstanceContext> = newConcurrentMap()
  private val leaseListeners = CopyOnWriteArrayList<LeaseListener>()

  /** Registers a listener for lease lifecycle events (expiry, healing, failure). */
  fun addLeaseListener(listener: LeaseListener) {
    leaseListeners += listener
  }

  init {
    require(servicePath.isNotEmpty()) { "Service base path cannot be empty" }
  }

  internal class ServiceInstanceContext(
    val service: ServiceInstance,
    val client: Client,
    val instancePath: String,
  ) : Closeable {
    // The JSON re-put on every heal; updateService refreshes it so a heal that
    // races an update never resurrects a stale payload.
    @Volatile
    var currentJson: String = service.toJson()
    var healer: SelfHealingKeepAlive by nonNullableReference()

    override fun close() {
      // Closing the healer stops any healing and revokes the current lease, so the
      // instance key is released promptly on unregister/close instead of lingering
      // until TTL (#7). Revoking already deletes the lease-bound key; deleteKey is
      // kept as belt-and-suspenders.
      healer.close()
      client.deleteKey(instancePath)  // best-effort; healer already revoked the lease
    }
  }

  @Synchronized
  @Throws(EtcdRecipeException::class)
  fun registerService(service: ServiceInstance) {
    checkCloseNotCalled()

    val instancePath = getNamesPath(service)
    val context = ServiceInstanceContext(service, client, instancePath)

    // Self-healing: if the instance lease expires (partition longer than the TTL),
    // the healer re-grants it and re-runs this CAS, re-registering the instance.
    // Re-registration is safe because instance ids are unique to this process; a
    // CAS loss means the key unexpectedly exists, so ownership is not reclaimed.
    try {
      context.healer = client.selfHealingKeepAlive(
        leaseTtlSecs.seconds,
        resilience.lease,
        leaseListener = { event -> onLeaseEvent(instancePath, event) },
      ) { lease ->
        client.transaction(resilience.rpc) {
          If(instancePath.doesNotExist)
          Then(instancePath.setTo(context.currentJson, putOption { withLeaseId(lease.id) }))
        }.isSucceeded
      }
    } catch (e: EtcdRecipeRuntimeException) {
      // Initial CAS lost (key already present); the healer already revoked its lease.
      logger.debug(e) { "Registration CAS lost for $instancePath" }
      throw EtcdRecipeException("Service registration failed for $instancePath")
    }

    // Only publish a fully-built context (healer set) into the map.
    serviceContextMap[service.id] = context
  }

  @Synchronized
  @Throws(EtcdRecipeException::class)
  fun updateService(service: ServiceInstance) {
    checkCloseNotCalled()
    val instancePath = getNamesPath(service)
    val context = serviceContextMap[service.id]
      ?: throw EtcdRecipeException("ServiceInstance ${service.name} was not first registered with registerService()")
    val txn =
      client.transaction(resilience.rpc) {
        If(instancePath.doesExist)
        Then(instancePath.setTo(service.toJson(), putOption { withLeaseId(context.healer.currentLeaseId) }))
      }
    if (!txn.isSucceeded) throw EtcdRecipeException("Service update failed for $instancePath")
    context.currentJson = service.toJson()
  }

  // Record lease trouble on the exceptions list the way the old keep-alive error
  // callback did, drive connection state, and forward to user listeners.
  @Suppress("TooGenericExceptionCaught")
  private fun onLeaseEvent(
    instancePath: String,
    event: LeaseEvent,
  ) {
    reportLeaseEvent(event)
    when (event) {
      is LeaseEvent.Suspended -> {
        exceptionList.value += event.cause
      }

      is LeaseEvent.Expired -> {
        event.cause?.let { exceptionList.value += it }
        ?: run { exceptionList.value += EtcdRecipeRuntimeException("Lease for $instancePath expired; healing") }
      }

      is LeaseEvent.Failed -> {
        exceptionList.value += event.cause
        ?: EtcdRecipeRuntimeException("Lease healing for $instancePath abandoned; instance is unregistered")
      }

      is LeaseEvent.Restored -> {
        logger.info {
        "Lease for $instancePath healed: ${event.oldLeaseId} -> ${event.newLeaseId}"
      }
      }
    }
    leaseListeners.forEach { listener ->
      try {
        listener.onLeaseEvent(event)
      } catch (e: Throwable) {
        logger.error(e) { "Exception in lease listener" }
        exceptionList.value += e
      }
    }
  }

  @Synchronized
  @Throws(EtcdRecipeException::class)
  fun unregisterService(service: ServiceInstance) {
    checkCloseNotCalled()
    val found = internalUnregisterService(service)
    if (!found) throw EtcdRecipeException("ServiceInstance not published with registerService()")
  }

  private fun internalUnregisterService(service: ServiceInstance): Boolean {
    val context = serviceContextMap.remove(service.id)
    context?.close()
    return context != null
  }

  @Synchronized
  override fun doClose() {
    serviceContextMap.forEach { (_, v) -> internalUnregisterService(v.service) }
  }

  internal val namesBasePath: String get() = namesPath

  private fun getNamesPath(service: ServiceInstance) = namesPath.appendToPath("${service.name}/${service.id}")

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
