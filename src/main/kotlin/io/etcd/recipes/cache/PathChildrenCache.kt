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

package io.etcd.recipes.cache

import com.google.common.collect.Maps
import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.delegate.AtomicDelegates
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.watch.WatchEvent.EventType.*
import io.etcd.recipes.common.*
import io.etcd.recipes.discovery.ServiceCache
import java.io.Closeable
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ExecutorService

class PathChildrenCache(val urls: List<String>,
                        val cachePath: String,
                        val cacheData: Boolean,
                        executorService: ExecutorService? = null) : EtcdConnector(urls), Closeable {

    private var startCalled by AtomicDelegates.atomicBoolean(false)
    private var dataPreloaded = BooleanMonitor(false)
    private val cacheMap: ConcurrentMap<String, ByteSequence> = Maps.newConcurrentMap()

    private val listeners = emptyList<PathChildrenCacheListener>()

    fun start(buildInitial: Boolean = false) {
        semaphore.withLock {
            if (startCalled)
                throw EtcdRecipeRuntimeException("start() already called")
            checkCloseNotCalled()

            watchClient.watcher(cachePath, cachePath.asPrefixWatchOption) { watchResponse ->
                watchResponse.events
                    .forEach { event ->
                        when (event.eventType) {
                            PUT          -> {
                                val (k, v) = event.keyValue.asPair.asString
                                //val isNew = !serviceMap.containsKey(k)
                                //serviceMap[k] = v
                                //println("$k ${if (newKey) "added" else "updated"}")
                                listeners.forEach {
                                    try {
                                        //it.cacheChanged(PUT, isNew, k, ServiceInstance.toObject(v))
                                    } catch (e: Throwable) {
                                        ServiceCache.logger.error(e) { "Exception in cacheChanged()" }
                                        //exceptionHolder.exception = e
                                    }
                                }
                                //println("$k $v ${if (newKey) "added" else "updated"}")
                            }
                            DELETE       -> {
                                val k = event.keyValue.key.asString
                                //val prevValue = serviceMap.remove(k)?.let { ServiceInstance.toObject(it) }
                                listeners.forEach {
                                    try {
                                        //it.cacheChanged(DELETE, false, k, prevValue)
                                    } catch (e: Throwable) {
                                        ServiceCache.logger.error(e) { "Exception in cacheChanged()" }
                                        //exceptionHolder.exception = e
                                    }
                                }
                                println("$k deleted")
                            }
                            UNRECOGNIZED -> ServiceCache.logger.error { "Unrecognized error with $cachePath watch" }
                            else         -> ServiceCache.logger.error { "Unknown error with $cachePath watch" }
                        }
                    }
            }

            // Preload with initial data
            val kvs = kvClient.getKeyValues(cachePath)
            for (kv in kvs) {
                val (k, v) = kv
                cacheMap[k] = v
            }

            dataPreloaded.set(true)
            startCalled = true
        }
    }

    fun getCurrentData(): List<ByteSequence> {
        return emptyList()
    }

    fun getCurrentData(fullPath: String): ByteSequence? {
        return cacheMap.get(fullPath)
    }

    fun clear() {
        cacheMap.clear()
    }
}