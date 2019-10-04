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
import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.delegate.AtomicDelegates.nonNullableReference
import com.sudothought.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.op.CmpTarget
import org.athenian.jetcd.appendToPath
import org.athenian.jetcd.asPutOption
import org.athenian.jetcd.keepAliveUntil
import org.athenian.jetcd.putOp
import org.athenian.jetcd.transaction
import java.io.Closeable
import java.util.concurrent.Executors

class ServiceDiscovery<T>(val url: String,
                          val basePath: String,
                          val clientId: String = "Client:${randomId(9)}") : Closeable {

    private val executor = lazy { Executors.newCachedThreadPool() }
    private var client by nonNullableReference<Client>()
    private var leaseClient by nonNullableReference<Lease>()
    private var kvClient by nonNullableReference<KV>()
    private var startCalled by atomicBoolean(false)
    private val servicePath = basePath.appendToPath(clientId)
    private val serviceContextMap = Maps.newConcurrentMap<String, ServiceInstanceContext>()

    class ServiceInstanceContext(val service: ServiceInstance) {
        var lease by nonNullableReference<LeaseGrantResponse>()
        val closeKeepAlive = BooleanMonitor(true)
    }

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(basePath.isNotEmpty()) { "Service base path cannot be empty" }
    }

    fun start() {
        client = Client.builder().endpoints(url).build()
        leaseClient = client.leaseClient
        kvClient = client.kvClient
        startCalled = true
    }

    private fun checkStartCalled() {
        check(startCalled) { "start() must be called first" }
    }

    fun registerService(service: ServiceInstance) {

        checkStartCalled()

        val context = ServiceInstanceContext(service)

        serviceContextMap[service.id] = context

        // Prime lease with 2 seconds to give keepAlive a chance to get started
        context.lease = leaseClient.grant(2).get()

        val txn =
            kvClient.transaction {
                If(org.athenian.jetcd.equals(servicePath, CmpTarget.version(0)))
                Then(putOp(servicePath, service.toJson(), context.lease.asPutOption))
            }

        if (!txn.isSucceeded) throw ServiceDiscoveryException("Service registration failed for $servicePath")

        // Run keep-alive until closed
        executor.value.submit {
            leaseClient.keepAliveUntil(context.lease) { context.closeKeepAlive.waitUntilFalse() }
            println("Ending keep-alive")
        }
    }

    fun updateService(service: ServiceInstance) {
        checkStartCalled()

        val context = serviceContextMap[service.id]
        if (context == null)
            throw ServiceDiscoveryException("ServiceInstance not already registered with registerService()")

        val txn =
            kvClient.transaction {
                If(org.athenian.jetcd.equals(servicePath, CmpTarget.version(0)))
                Else(putOp(servicePath, service.toJson(), context.lease.asPutOption))
            }

        if (txn.isSucceeded) throw ServiceDiscoveryException("Service update failed for $servicePath")
    }

    fun unregisterService(service: ServiceInstance) {
        checkStartCalled()

        serviceContextMap[service.id]?.closeKeepAlive?.set(false)
            ?: throw ServiceDiscoveryException("ServiceInstance not already registered with registerService()")

        serviceContextMap.remove(service.id)
    }

    fun serviceCacheBuilder(): ServiceCacheBuilder<T>? {
        checkStartCalled()
        return null
    }

    fun queryForNames(): Collection<String?>? {
        checkStartCalled()
        return null
    }

    fun queryForInstances(name: String): Collection<ServiceInstance?>? {
        checkStartCalled()
        return null
    }

    fun queryForInstance(name: String, id: String): ServiceInstance? {
        return null
    }

    //fun serviceProviderBuilder(): ServiceProviderBuilder<T?>? {return null}

    override fun close() {

        serviceContextMap.forEach { k, v ->
            v.closeKeepAlive.set(false)
        }

        if (startCalled) {
            kvClient.close()
            leaseClient.close()
            client.close()
        }

        if (executor.isInitialized())
            executor.value.shutdown()
    }
}
