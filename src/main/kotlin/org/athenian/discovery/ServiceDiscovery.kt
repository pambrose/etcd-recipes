/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.athenian.discovery

import com.google.common.collect.Maps
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.delegate.AtomicDelegates.nonNullableReference
import com.sudothought.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.jetcd.CloseableClient
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.op.CmpTarget
import org.athenian.jetcd.appendToPath
import org.athenian.jetcd.asPutOption
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getChildrenStringValues
import org.athenian.jetcd.getStringValue
import org.athenian.jetcd.keepAlive
import org.athenian.jetcd.putOp
import org.athenian.jetcd.transaction
import java.io.Closeable
import java.util.concurrent.Semaphore

class ServiceDiscovery<T>(val url: String,
                          basePath: String,
                          val clientId: String) : Closeable {

    // Java constructor
    constructor(url: String, basePath: String) : this(url, basePath, "Client:${randomId(9)}")

    private val semaphore = Semaphore(1, true)
    private var client by nonNullableReference<Client>()
    private var leaseClient by nonNullableReference<Lease>()
    private var kvClient by nonNullableReference<KV>()
    private var startCalled by atomicBoolean(false)
    private var closeCalled by atomicBoolean(false)
    private val namesPath = basePath.appendToPath("/names")
    private val serviceContextMap = Maps.newConcurrentMap<String, ServiceInstanceContext>()

    class ServiceInstanceContext(val service: ServiceInstance) : Closeable {
        var lease by nonNullableReference<LeaseGrantResponse>()
        var keepAlive by nonNullableReference<CloseableClient>()

        override fun close() {
            keepAlive.close()
        }
    }

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(basePath.isNotEmpty()) { "Service base path cannot be empty" }
    }

    fun start() {
        semaphore.withLock {
            if (startCalled)
                throw ServiceDiscoveryException("start() already called")
            if (closeCalled)
                throw ServiceDiscoveryException("close() already called")
            client = Client.builder().endpoints(url).build()
            leaseClient = client.leaseClient
            kvClient = client.kvClient
            startCalled = true
        }
    }

    fun registerService(service: ServiceInstance) {
        semaphore.withLock {
            checkStatus()

            val instancePath = getNamesPath(service)
            val context = ServiceInstanceContext(service)

            serviceContextMap[service.id] = context

            // Prime lease with 2 seconds to give keepAlive a chance to get started
            context.lease = leaseClient.grant(2).get()

            val txn =
                kvClient.transaction {
                    If(org.athenian.jetcd.equals(instancePath, CmpTarget.version(0)))
                    Then(putOp(instancePath, service.toJson(), context.lease.asPutOption))
                }

            if (!txn.isSucceeded) throw ServiceDiscoveryException("Service registration failed for $instancePath")

            // Run keep-alive until closed
            context.keepAlive = leaseClient.keepAlive(context.lease)
        }
    }

    fun updateService(service: ServiceInstance) {
        semaphore.withLock {
            checkStatus()
            val instancePath = getNamesPath(service)
            val context = serviceContextMap[service.id]
                ?: throw ServiceDiscoveryException("ServiceInstance not already registered with registerService()")
            val txn =
                kvClient.transaction {
                    If(org.athenian.jetcd.equals(instancePath, CmpTarget.version(0)))
                    Else(putOp(instancePath, service.toJson(), context.lease.asPutOption))
                }
            if (txn.isSucceeded) throw ServiceDiscoveryException("Service update failed for $instancePath")
        }
    }

    fun unregisterService(service: ServiceInstance) {
        semaphore.withLock {
            checkStatus()
            serviceContextMap[service.id]?.close()
                ?: throw ServiceDiscoveryException("ServiceInstance not published with registerService()")
            serviceContextMap.remove(service.id)
        }
    }

    fun serviceCache(name: String): ServiceCache {
        checkStatus()
        return ServiceCache(url, namesPath, name)
    }

    fun queryForNames(): List<String> =
        semaphore.withLock {
            checkStatus()
            kvClient.getChildrenKeys(namesPath)
        }

    fun queryForInstances(name: String): List<ServiceInstance> =
        semaphore.withLock {
            checkStatus()
            kvClient.getChildrenStringValues(getNamesPath(name)).map { ServiceInstance.toObject(it) }
        }

    fun queryForInstance(name: String, id: String): ServiceInstance =
        semaphore.withLock {
            checkStatus()
            val path = getNamesPath(name, id)
            val json = kvClient.getStringValue(path)
                ?: throw ServiceDiscoveryException("ServiceInstance $path not present")
            ServiceInstance.toObject(json)
        }

    //fun serviceProviderBuilder(): ServiceProviderBuilder<T?>? {return null}

    override fun close() {
        semaphore.withLock {
            if (startCalled && !closeCalled) {
                serviceContextMap.forEach { k, v -> unregisterService(v.service) }
                kvClient.close()
                leaseClient.close()
                client.close()
            }
            closeCalled = true
        }
    }

    private fun checkStatus() {
        if (!startCalled)
            throw ServiceDiscoveryException("start() must be called first")
        if (closeCalled)
            throw ServiceDiscoveryException("close() already called")
    }

    private fun getNamesPath(service: ServiceInstance) = getNamesPath(service.name, service.id)

    private fun getNamesPath(vararg elems: String) = namesPath.appendToPath(elems.joinToString("/"))
}
