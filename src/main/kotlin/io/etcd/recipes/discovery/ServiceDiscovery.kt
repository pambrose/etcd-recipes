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
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.op.CmpTarget
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.jetcd.*
import java.io.Closeable

data class ServiceDiscovery(val urls: List<String>,
                            private val basePath: String,
                            val clientId: String) : EtcdConnector(urls), Closeable {

    // For Java clients
    constructor(urls: List<String>, basePath: String) : this(urls, basePath, "Client:${randomId(7)}")

    private val namesPath = basePath.appendToPath("/names")
    private val serviceContextMap = Maps.newConcurrentMap<String, ServiceInstanceContext>()
    private val serviceCacheList = mutableListOf<ServiceCache>()

    init {
        require(urls.isNotEmpty()) { "URL cannot be empty" }
        require(basePath.isNotEmpty()) { "Service base path cannot be empty" }
    }

    private class ServiceInstanceContext(val service: ServiceInstance) : Closeable {
        var lease: LeaseGrantResponse by nonNullableReference<LeaseGrantResponse>()
        var keepAlive: CloseableClient by nonNullableReference<CloseableClient>()

        override fun close() {
            keepAlive.close()
        }
    }

    @Throws(EtcdRecipeException::class)
    fun registerService(service: ServiceInstance) {
        semaphore.withLock {
            checkCloseNotCalled()

            val instancePath = getNamesPath(service)
            val context = ServiceInstanceContext(service)

            serviceContextMap[service.id] = context

            // Prime lease with 2 seconds to give keepAlive a chance to get started
            context.lease = leaseClient.grant(2).get()

            val txn =
                kvClient.transaction {
                    If(equalTo(instancePath, CmpTarget.version(0)))
                    Then(putOp(instancePath, service.toJson(), context.lease.asPutOption))
                    Else()
                }

            if (!txn.isSucceeded) throw EtcdRecipeException("Service registration failed for $instancePath")

            // Run keep-alive until closed
            context.keepAlive = leaseClient.keepAlive(context.lease)
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
                    If(equalTo(instancePath, CmpTarget.version(0)))
                    Then()
                    Else(putOp(instancePath, service.toJson(), context.lease.asPutOption))
                }
            if (txn.isSucceeded) throw EtcdRecipeException("Service update failed for $instancePath")
        }
    }

    @Throws(EtcdRecipeException::class)
    fun unregisterService(service: ServiceInstance) {
        semaphore.withLock {
            checkCloseNotCalled()
            serviceContextMap[service.id]?.close()
                ?: throw EtcdRecipeException("ServiceInstance not published with registerService()")
            serviceContextMap.remove(service.id)
        }
    }

    fun serviceCache(name: String): ServiceCache {
        checkCloseNotCalled()
        val cache = ServiceCache(urls, namesPath, name)
        serviceCacheList += cache
        return cache
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

    //fun serviceProviderBuilder(): ServiceProviderBuilder<T?>? {return null}

    override fun close() {
        semaphore.withLock {
            if (!closeCalled) {
                // Close all service caches
                serviceCacheList.forEach { it.close() }

                if (!closeCalled)
                    serviceContextMap.forEach { (_, v) -> unregisterService(v.service) }

                super.close()
            }
        }
    }

    private fun getNamesPath(service: ServiceInstance) = getNamesPath(service.name, service.id)

    private fun getNamesPath(vararg elems: String) = namesPath.appendToPath(elems.joinToString("/"))
}
