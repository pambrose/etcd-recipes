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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package org.athenian.discovery

import com.google.common.collect.Maps
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.delegate.AtomicDelegates
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import mu.KLogging
import org.athenian.common.EtcdRecipeRuntimeException
import org.athenian.jetcd.appendToPath
import org.athenian.jetcd.asPair
import org.athenian.jetcd.asString
import org.athenian.jetcd.nullWatchOption
import org.athenian.jetcd.watcher
import java.io.Closeable
import java.util.concurrent.Semaphore

class ServiceCache(val url: String,
                   namesPath: String,
                   val serviceName: String) : Closeable {

    private val semaphore = Semaphore(1, true)
    private var client by AtomicDelegates.nonNullableReference<Client>()
    private var watchClient by AtomicDelegates.nonNullableReference<Watch>()
    private var startCalled by AtomicDelegates.atomicBoolean(false)
    private var closeCalled by AtomicDelegates.atomicBoolean(false)
    private val watchPath = namesPath.appendToPath(serviceName)
    private val serviceMap = Maps.newConcurrentMap<String, String>()
    private val listeners = mutableListOf<ServiceCacheListener>()

    init {
        require(serviceName.isNotEmpty()) { "ServiceCache service name cannot be empty" }
    }

    fun start() {
        semaphore.withLock {
            if (startCalled)
                throw EtcdRecipeRuntimeException("start() already called")
            checkCloseNotCalled()

            client = Client.builder().endpoints(url).build()

            watchClient = client.watchClient
            watchClient.watcher(watchPath, nullWatchOption) { watchResponse ->
                watchResponse.events
                    .forEach { event ->
                        when (event.eventType) {
                            PUT -> {
                                val (k, v) = event.keyValue.asPair.asString
                                val newKey = !serviceMap.containsKey(k)
                                serviceMap[k] = v
                                listeners.forEach { it.cacheChanged(PUT, k, ServiceInstance.toObject(v)) }
                                //println("$k $v ${if (newKey) "added" else "updated"}")
                            }
                            DELETE -> {
                                val k = event.keyValue.key.asString
                                val prevValue = serviceMap.remove(k)?.let { ServiceInstance.toObject(it) }
                                listeners.forEach { it.cacheChanged(DELETE, k, prevValue) }
                                //println("$k deleted")
                            }
                            UNRECOGNIZED -> logger.error { "Error with $watchPath watch" }
                            else -> logger.error { "Error with watch" }
                        }
                    }
            }

            startCalled = true
        }
    }

    val instances: List<ServiceInstance>
        get() {
            checkCloseNotCalled()
            return serviceMap.values.map { ServiceInstance.toObject(it) }
        }

    fun addListenerForChanges(listener: (eventType: WatchEvent.EventType,
                                         serviceName: String,
                                         serviceInstance: ServiceInstance?) -> Unit) {
        addListenerForChanges(
            object : ServiceCacheListener {
                override fun cacheChanged(eventType: WatchEvent.EventType,
                                          serviceName: String,
                                          serviceInstance: ServiceInstance?) {
                    listener.invoke(eventType, serviceName, serviceInstance)
                }
            })
    }

    fun addListenerForChanges(listener: ServiceCacheListener) {
        checkCloseNotCalled()
        listeners += listener
    }

    private fun checkCloseNotCalled() {
        if (closeCalled) throw EtcdRecipeRuntimeException("close() already closed")
    }

    override fun close() {
        semaphore.withLock {
            if (startCalled && !closeCalled) {
                watchClient.close()
                client.close()
            }
            closeCalled = true
        }
    }

    companion object : KLogging()
}
