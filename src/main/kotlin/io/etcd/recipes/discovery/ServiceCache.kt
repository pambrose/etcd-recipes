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
import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.asPrefixWatchOption
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.ensureTrailing
import io.etcd.recipes.common.getChildren
import io.etcd.recipes.common.watcher
import mu.KLogging
import java.io.Closeable
import java.util.*
import java.util.concurrent.ConcurrentMap

class ServiceCache internal constructor(val urls: List<String>,
                                        val namesPath: String,
                                        val serviceName: String) : EtcdConnector(urls), Closeable {

    private var startCalled by atomicBoolean(false)
    private var dataPreloaded = BooleanMonitor(false)
    private val servicePath = namesPath.appendToPath(serviceName)
    private val serviceMap: ConcurrentMap<String, String> = Maps.newConcurrentMap()
    private val listeners: MutableList<ServiceCacheListener> = Collections.synchronizedList(mutableListOf())

    init {
        require(serviceName.isNotEmpty()) { "ServiceCache service name cannot be empty" }
    }

    @Synchronized
    fun start(): ServiceCache {
        if (startCalled)
            throw EtcdRecipeRuntimeException("start() already called")
        checkCloseNotCalled()

        val adjustedServicePath = servicePath.ensureTrailing("/")
        val adjustedNamesPath = namesPath.ensureTrailing("/")

        watchClient.watcher(adjustedServicePath, adjustedServicePath.asPrefixWatchOption) { watchResponse ->
            // Wait for data to be loaded
            dataPreloaded.waitUntilTrue()

            watchResponse.events
                .forEach { event ->
                    val (k, v) = event.keyValue.asPair.asString
                    val stripped = k.substring(adjustedNamesPath.length)
                    when (event.eventType) {
                        PUT          -> {
                            val isAdd = !serviceMap.containsKey(stripped)
                            serviceMap[stripped] = v
                            listeners.forEach { listener ->
                                try {
                                    listener.cacheChanged(PUT, isAdd, stripped, ServiceInstance.toObject(v))
                                } catch (e: Throwable) {
                                    logger.error(e) { "Exception in cacheChanged()" }
                                    exceptionList.value += e
                                }
                            }
                            //println("$k $v ${if (newKey) "added" else "updated"}")
                        }
                        DELETE       -> {
                            val prevValue = serviceMap.remove(stripped)?.let { ServiceInstance.toObject(it) }
                            listeners.forEach { listener ->
                                try {
                                    listener.cacheChanged(DELETE, false, stripped, prevValue)
                                } catch (e: Throwable) {
                                    logger.error(e) { "Exception in cacheChanged()" }
                                    exceptionList.value += e
                                }
                            }
                            //println("$stripped deleted")
                        }
                        UNRECOGNIZED -> logger.error { "Unrecognized error with $servicePath watch" }
                        else         -> logger.error { "Unknown error with $servicePath watch" }
                    }
                }
        }

        // Preload with initial data
        val kvs = kvClient.getChildren(adjustedServicePath)
        for (kv in kvs) {
            val (k, v) = kv
            val stripped = k.substring(adjustedNamesPath.length)
            serviceMap[stripped] = v.asString
        }

        dataPreloaded.set(true)
        startCalled = true

        return this
    }

    val instances: List<ServiceInstance>
        get() {
            checkCloseNotCalled()
            return serviceMap.values.map { ServiceInstance.toObject(it) }
        }

    fun addListenerForChanges(listener: (eventType: WatchEvent.EventType,
                                         isAdd: Boolean,
                                         serviceName: String,
                                         serviceInstance: ServiceInstance?) -> Unit) {
        addListenerForChanges(
            object : ServiceCacheListener {
                override fun cacheChanged(eventType: WatchEvent.EventType,
                                          isAdd: Boolean,
                                          serviceName: String,
                                          serviceInstance: ServiceInstance?) {
                    listener.invoke(eventType, isAdd, serviceName, serviceInstance)
                }
            })
    }

    fun addListenerForChanges(listener: ServiceCacheListener) {
        checkCloseNotCalled()
        listeners += listener
    }

    companion object : KLogging()
}
