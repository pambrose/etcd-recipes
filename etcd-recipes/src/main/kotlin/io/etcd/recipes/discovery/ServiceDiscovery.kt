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

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.DEFAULT_TTL_SECS
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getChildrenValues
import io.etcd.recipes.common.getValue
import io.etcd.recipes.discovery.ServiceDiscovery.Companion.defaultClientId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList

@JvmOverloads
fun <T> withServiceDiscovery(
  client: Client,
  servicePath: String,
  leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  clientId: String = defaultClientId(),
  receiver: ServiceDiscovery.() -> T,
): T = ServiceDiscovery(client, servicePath, leaseTtlSecs, clientId).use { it.receiver() }

/**
 * Façade combining the write-side ([ServiceRegistry]) and the read-side
 * (queries, caches, providers) of service discovery. New code that only
 * needs one side can depend on [ServiceRegistry], [ServiceCache], or
 * [ServiceProvider] directly without pulling in the rest.
 */
class ServiceDiscovery
@JvmOverloads
constructor(
  client: Client,
  val servicePath: String,
  val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  val clientId: String = defaultClientId(),
  private val resilienceConfig: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilienceConfig) {
  private val registry = ServiceRegistry(client, servicePath, leaseTtlSecs, resilienceConfig)
  private val namesPath = servicePath.appendToPath("/names")
  private val serviceCacheList: MutableList<ServiceCache> = CopyOnWriteArrayList()
  private val serviceProviderList: MutableList<ServiceProvider> = CopyOnWriteArrayList()

  init {
    require(servicePath.isNotEmpty()) { "Service base path cannot be empty" }
  }

  @Throws(EtcdRecipeException::class)
  fun registerService(service: ServiceInstance) {
    checkCloseNotCalled()
    registry.registerService(service)
  }

  @Throws(EtcdRecipeException::class)
  fun updateService(service: ServiceInstance) {
    checkCloseNotCalled()
    registry.updateService(service)
  }

  @Throws(EtcdRecipeException::class)
  fun unregisterService(service: ServiceInstance) {
    checkCloseNotCalled()
    registry.unregisterService(service)
  }

  fun serviceCache(name: String): ServiceCache {
    checkCloseNotCalled()
    val cache = ServiceCache(client, namesPath, name)
    serviceCacheList += cache
    return cache
  }

  fun <T> withServiceCache(
    name: String,
    receiver: ServiceCache.() -> T,
  ) = serviceCache(name).use { it.receiver() }

  fun serviceProvider(serviceName: String): ServiceProvider {
    checkCloseNotCalled()
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
    return client.getChildrenValues(namesPath.appendToPath(name)).map {
      ServiceInstance.toObject(it.asString)
    }
  }

  @Synchronized
  @Throws(EtcdRecipeException::class)
  fun queryForInstance(
    name: String,
    id: String,
  ): ServiceInstance {
    checkCloseNotCalled()
    val path = namesPath.appendToPath("$name/$id")
    val json = client.getValue(path, resilience.rpc)?.asString
      ?: throw EtcdRecipeException("ServiceInstance $path not present")
    return ServiceInstance.toObject(json)
  }

  @Synchronized
  override fun doClose() {
    // Close all child caches and providers tracked by this façade.
    serviceCacheList.forEach { runCatching { it.close() } }
    serviceProviderList.forEach { runCatching { it.close() } }
    registry.close()
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    internal fun defaultClientId() = defaultClientId(ServiceDiscovery::class.simpleName!!)
  }
}
