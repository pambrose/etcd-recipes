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
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType.*
import io.etcd.recipes.common.*
import mu.KLogging
import java.io.Closeable
import java.util.*

class ServiceCache internal constructor(val urls: List<String>,
                                        namesPath: String,
                                        val serviceName: String
                                       ) : EtcdConnector(urls), Closeable {

    private var startCalled by atomicBoolean(false)
    private val servicePath = namesPath.appendToPath(serviceName)
    private val serviceMap = Maps.newConcurrentMap<String, String>()
    private val listeners = Collections.synchronizedList(mutableListOf<ServiceCacheListener>())

    init {
        require(serviceName.isNotEmpty()) { "ServiceCache service name cannot be empty" }
    }

    fun start() {
        semaphore.withLock {
            if (startCalled)
                throw EtcdRecipeRuntimeException("start() already called")
            checkCloseNotCalled()

            watchClient.watcher(servicePath, nullWatchOption) { watchResponse ->
                watchResponse.events
                    .forEach { event ->
                        when (event.eventType) {
                            PUT          -> {
                                val (k, v) = event.keyValue.asPair.asString
                                val newKey = !serviceMap.containsKey(k)
                                serviceMap[k] = v
                                //println("$k ${if (newKey) "added" else "updated"}")

                                listeners.forEach {
                                    try {
                                        it.cacheChanged(PUT, k, ServiceInstance.toObject(v))
                                    } catch (e: Throwable) {
                                        logger.error(e) { "Exception in cacheChanged()" }
                                        e.printStackTrace()
                                    }
                                }
                                //println("$k $v ${if (newKey) "added" else "updated"}")
                            }
                            DELETE       -> {
                                val k = event.keyValue.key.asString
                                val prevValue = serviceMap.remove(k)?.let { ServiceInstance.toObject(it) }
                                listeners.forEach {
                                    try {
                                        it.cacheChanged(DELETE, k, prevValue)
                                    } catch (e: Throwable) {
                                        logger.error(e) { "Exception in cacheChanged()" }
                                        e.printStackTrace()
                                    }
                                }
                                println("$k deleted")
                            }
                            UNRECOGNIZED -> logger.error { "Unrecognized error with $servicePath watch" }
                            else         -> logger.error { "Unknown error with $servicePath watch" }
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

    override fun close() {
        semaphore.withLock {
            super.close()
        }
    }

    companion object : KLogging()
}
