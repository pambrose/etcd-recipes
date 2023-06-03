/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.randomId
import com.google.common.collect.Maps.newConcurrentMap
import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.recipes.barrier.DistributedDoubleBarrier.Companion.defaultClientId
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.defaultTtlSecs
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getChildrenValues
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.keepAlive
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import mu.two.KLogging
import java.io.Closeable
import java.util.Collections.synchronizedList
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration.Companion.seconds

@JvmOverloads
fun <T> withServiceDiscovery(
  client: Client,
  servicePath: String,
  leaseTtlSecs: Long = defaultTtlSecs,
  clientId: String = defaultClientId(),
  receiver: ServiceDiscovery.() -> T
): T =
  ServiceDiscovery(client, servicePath, leaseTtlSecs, clientId).use { it.receiver() }

class ServiceDiscovery
@JvmOverloads
constructor(
  client: Client,
  val servicePath: String,
  val leaseTtlSecs: Long = defaultTtlSecs,
  val clientId: String = defaultClientId()
) : EtcdConnector(client) {

  private val namesPath = servicePath.appendToPath("/names")
  private val serviceContextMap: ConcurrentMap<String, ServiceInstanceContext> = newConcurrentMap()
  private val serviceCacheList: MutableList<ServiceCache> = synchronizedList(mutableListOf())
  private val serviceProviderList: MutableList<ServiceProvider> = synchronizedList(mutableListOf())

  init {
    require(servicePath.isNotEmpty()) { "Service base path cannot be empty" }
  }

  private class ServiceInstanceContext(
    val service: ServiceInstance,
    val client: Client,
    val instancePath: String
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

    // Prime lease with leaseTtlSecs seconds to give keepAlive a chance to get started
    context.lease = client.leaseGrant(leaseTtlSecs.seconds)

    val txn =
      client.transaction {
        If(instancePath.doesNotExist)
        Then(instancePath.setTo(service.toJson(), putOption { withLeaseId(context.lease.id) }))
      }

    // Run keep-alive until closed
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
    val context = serviceContextMap[service.id]
    context?.close()
    serviceContextMap.remove(service.id)
    return context.isNotNull()
  }

  fun serviceCache(name: String): ServiceCache {
    checkCloseNotCalled()
    val cache = ServiceCache(client, namesPath, name)
    serviceCacheList += cache
    return cache
  }

  fun <T> withServiceCache(name: String, receiver: ServiceCache.() -> T) = serviceCache(name).use { it.receiver() }

  fun serviceProvider(serviceName: String): ServiceProvider {
    val provider = ServiceProvider(client, namesPath, serviceName)
    serviceProviderList += provider
    return provider
  }

  @Synchronized
  fun queryForNames(): List<String> {
    checkCloseNotCalled()
    return client.getChildrenKeys(namesPath)
  }

  @Synchronized
  fun queryForInstances(name: String): List<ServiceInstance> {
    checkCloseNotCalled()
    return client.getChildrenValues(getNamesPath(name)).map { it.asString }.map { ServiceInstance.toObject(it) }
  }

  @Synchronized
  @Throws(EtcdRecipeException::class)
  fun queryForInstance(name: String, id: String): ServiceInstance {
    checkCloseNotCalled()
    val path = getNamesPath(name, id)
    val json = client.getValue(path)?.asString
      ?: throw EtcdRecipeException("ServiceInstance $path not present")
    return ServiceInstance.toObject(json)
  }

  @Synchronized
  override fun close() {
    if (closeCalled)
      return

    // Close all service caches
    serviceCacheList.forEach { it.close() }
    serviceContextMap.forEach { (_, v) -> internalUnregisterService(v.service) }

    super.close()
  }

  private fun getNamesPath(service: ServiceInstance) = getNamesPath(service.name, service.id)

  private fun getNamesPath(vararg elems: String) = namesPath.appendToPath(elems.joinToString("/"))

  companion object : KLogging() {
    internal fun defaultClientId() = "${ServiceDiscovery::class.simpleName}:${randomId(tokenLength)}"
  }
}