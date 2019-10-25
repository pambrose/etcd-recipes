/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.discovery

import com.google.common.collect.Maps
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.delegate.AtomicDelegates.nonNullableReference
import com.sudothought.common.util.randomId
import io.etcd.jetcd.CloseableClient
import io.etcd.jetcd.KV
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asPutOption
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getKeys
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.getValues
import io.etcd.recipes.common.grant
import io.etcd.recipes.common.keepAlive
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import java.io.Closeable
import java.util.*
import java.util.concurrent.ConcurrentMap

data class ServiceDiscovery
@JvmOverloads
constructor(val urls: List<String>,
            private val basePath: String,
            val clientId: String = "Client:${randomId(7)}") : EtcdConnector(urls), Closeable {

    private val namesPath = basePath.appendToPath("/names")
    private val serviceContextMap: ConcurrentMap<String, ServiceInstanceContext> = Maps.newConcurrentMap()
    private val serviceCacheList: MutableList<ServiceCache> = Collections.synchronizedList(mutableListOf())
    private val serviceProviderList: MutableList<ServiceProvider> = Collections.synchronizedList(mutableListOf())

    init {
        require(urls.isNotEmpty()) { "URLs cannot be empty" }
        require(basePath.isNotEmpty()) { "Service base path cannot be empty" }
    }

    private class ServiceInstanceContext(val service: ServiceInstance,
                                         val kvClient: KV,
                                         val instancePath: String) : Closeable {
        var lease: LeaseGrantResponse by nonNullableReference()
        var keepAlive: CloseableClient by nonNullableReference()

        override fun close() {
            keepAlive.close()
            kvClient.delete(instancePath)
        }
    }

    @Throws(EtcdRecipeException::class)
    fun registerService(service: ServiceInstance) {
        semaphore.withLock {
            checkCloseNotCalled()

            val instancePath = getNamesPath(service)
            val context = ServiceInstanceContext(service, kvClient.value, instancePath)

            serviceContextMap[service.id] = context

            // Prime lease with 2 seconds to give keepAlive a chance to get started
            context.lease = leaseClient.grant(2).get()

            val txn =
                kvClient.transaction {
                    If(instancePath.doesNotExist)
                    Then(instancePath.setTo(service.toJson(), context.lease.asPutOption))
                }

            // Run keep-alive until closed
            if (txn.isSucceeded)
                context.keepAlive = leaseClient.keepAlive(context.lease)
            else
                throw EtcdRecipeException("Service registration failed for $instancePath")
        }
    }

    @Throws(EtcdRecipeException::class)
    fun updateService(service: ServiceInstance) {
        semaphore.withLock {
            checkCloseNotCalled()
            val instancePath = getNamesPath(service)
            val context = serviceContextMap[service.id]
                ?: throw EtcdRecipeException("ServiceInstance ${service.name} was not first registered with registerService()")
            val txn =
                kvClient.transaction {
                    If(instancePath.doesExist)
                    Then(instancePath.setTo(service.toJson(), context.lease.asPutOption))
                }
            if (!txn.isSucceeded) throw EtcdRecipeException("Service update failed for $instancePath")
        }
    }

    @Throws(EtcdRecipeException::class)
    fun unregisterService(service: ServiceInstance) {
        semaphore.withLock {
            checkCloseNotCalled()
            val found = internalUnregisterService(service)
            if (!found) throw EtcdRecipeException("ServiceInstance not published with registerService()")
        }
    }

    private fun internalUnregisterService(service: ServiceInstance): Boolean {
        val context = serviceContextMap[service.id]
        context?.close()
        serviceContextMap.remove(service.id)
        return context != null
    }

    fun serviceCache(name: String): ServiceCache {
        checkCloseNotCalled()
        val cache = ServiceCache(urls, namesPath, name)
        serviceCacheList += cache
        return cache
    }

    fun serviceProvider(serviceName: String): ServiceProvider {
        val provider = ServiceProvider(urls, namesPath, serviceName)
        serviceProviderList += provider
        return provider
    }

    fun queryForNames(): List<String> =
        semaphore.withLock {
            checkCloseNotCalled()
            kvClient.getKeys(namesPath)
        }

    fun queryForInstances(name: String): List<ServiceInstance> =
        semaphore.withLock {
            checkCloseNotCalled()
            kvClient.getValues(getNamesPath(name)).asString.map { ServiceInstance.toObject(it) }
        }

    @Throws(EtcdRecipeException::class)
    fun queryForInstance(name: String, id: String): ServiceInstance =
        semaphore.withLock {
            checkCloseNotCalled()
            val path = getNamesPath(name, id)
            val json = kvClient.getValue(path)?.asString
                ?: throw EtcdRecipeException("ServiceInstance $path not present")
            ServiceInstance.toObject(json)
        }

    override fun close() {
        semaphore.withLock {

            // Close all service caches
            serviceCacheList.forEach { it.close() }
            serviceContextMap.forEach { (_, v) -> internalUnregisterService(v.service) }

            super.close()
        }
    }

    private fun getNamesPath(service: ServiceInstance) = getNamesPath(service.name, service.id)

    private fun getNamesPath(vararg elems: String) = namesPath.appendToPath(elems.joinToString("/"))
}
