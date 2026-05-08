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
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.DEFAULT_TTL_SECS
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.keepAlive
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import java.io.Closeable
import java.util.concurrent.ConcurrentMap
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
) : EtcdConnector(client) {
  private val namesPath = servicePath.appendToPath("/names")
  private val serviceContextMap: ConcurrentMap<String, ServiceInstanceContext> = newConcurrentMap()

  init {
    require(servicePath.isNotEmpty()) { "Service base path cannot be empty" }
  }

  internal class ServiceInstanceContext(
    val service: ServiceInstance,
    val client: Client,
    val instancePath: String,
  ) : Closeable {
    var lease: LeaseGrantResponse by nonNullableReference()
    var keepAlive: CloseableClient by nonNullableReference()

    override fun close() {
      keepAlive.close()
      client.deleteKey(instancePath)
    }
  }

  @Synchronized
  @Throws(EtcdRecipeException::class)
  fun registerService(service: ServiceInstance) {
    checkCloseNotCalled()

    val instancePath = getNamesPath(service)
    val context = ServiceInstanceContext(service, client, instancePath)

    serviceContextMap[service.id] = context

    context.lease = client.leaseGrant(leaseTtlSecs.seconds)

    val txn =
      client.transaction {
        If(instancePath.doesNotExist)
        Then(instancePath.setTo(service.toJson(), putOption { withLeaseId(context.lease.id) }))
      }

    if (txn.isSucceeded)
      context.keepAlive = client.keepAlive(context.lease)
    else
      throw EtcdRecipeException("Service registration failed for $instancePath")
  }

  @Synchronized
  @Throws(EtcdRecipeException::class)
  fun updateService(service: ServiceInstance) {
    checkCloseNotCalled()
    val instancePath = getNamesPath(service)
    val context = serviceContextMap[service.id]
      ?: throw EtcdRecipeException("ServiceInstance ${service.name} was not first registered with registerService()")
    val txn =
      client.transaction {
        If(instancePath.doesExist)
        Then(instancePath.setTo(service.toJson(), putOption { withLeaseId(context.lease.id) }))
      }
    if (!txn.isSucceeded) throw EtcdRecipeException("Service update failed for $instancePath")
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
}
