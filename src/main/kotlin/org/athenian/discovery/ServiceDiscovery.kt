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

import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.delegate.AtomicDelegates.nonNullableReference
import com.sudothought.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
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

    private val executor = Executors.newFixedThreadPool(3)
    private val closeCalled = BooleanMonitor(false)
    private var client by nonNullableReference<Client>()
    private var leaseClient by nonNullableReference<Lease>()
    private var kvClient by nonNullableReference<KV>()
    private var startCalled by atomicBoolean(false)

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

    fun registerService(service: ServiceInstance) {
        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.grant(2).get()
        val servicePath = basePath.appendToPath(clientId)

        val txn =
            kvClient.transaction {
                If(org.athenian.jetcd.equals(servicePath, CmpTarget.version(0)))
                Then(putOp(servicePath, clientId, lease.asPutOption))
            }

        check(txn.isSucceeded) { "Service registration failed for $servicePath" }

        // Run keep-alive until closed
        executor.submit {
            leaseClient.keepAliveUntil(lease) { closeCalled.waitUntilTrue() }
        }
    }

    fun updateService(service: ServiceInstance?) {

    }

    fun unregisterService(service: ServiceInstance?) {}

    fun serviceCacheBuilder(): ServiceCacheBuilder<T>? {
        return null
    }

    fun queryForNames(): Collection<String?>? {
        return null
    }

    fun queryForInstances(name: String): Collection<ServiceInstance?>? {
        return null
    }

    fun queryForInstance(name: String, id: String): ServiceInstance? {
        return null
    }

    //fun serviceProviderBuilder(): ServiceProviderBuilder<T?>? {return null}

    override fun close() {
        if (startCalled) {
            kvClient.close()
            leaseClient.close()
            client.close()
        }
    }
}
