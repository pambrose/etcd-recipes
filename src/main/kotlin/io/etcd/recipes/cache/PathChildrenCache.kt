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
import com.sudothought.common.time.Conversions
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import io.etcd.recipes.cache.PathChildrenCache.StartMode.BUILD_INITIAL_CACHE
import io.etcd.recipes.cache.PathChildrenCache.StartMode.NORMAL
import io.etcd.recipes.cache.PathChildrenCache.StartMode.POST_INITIALIZED_EVENT
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_ADDED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_REMOVED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_UPDATED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.INITIALIZED
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.asPrefixWatchOption
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getKeyValues
import io.etcd.recipes.common.watcher
import mu.KLogging
import java.io.Closeable
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

class PathChildrenCache(val urls: List<String>,
                        val cachePath: String,
                        val cacheData: Boolean,
                        private val userExecutor: Executor? = null) : EtcdConnector(urls), Closeable {

    private var startCalled by AtomicDelegates.atomicBoolean(false)
    private val startThreadComplete = BooleanMonitor(false)
    private val closeSemaphore = Semaphore(1, true)
    private val cacheMap: ConcurrentMap<String, ByteSequence> = Maps.newConcurrentMap()
    private val listeners: List<PathChildrenCacheListener> = emptyList()
    // Use a single threaded executor to maintain order
    private val executor = userExecutor ?: Executors.newSingleThreadExecutor()

    enum class StartMode {
        /**
         * cache will _not_ be primed. i.e. it will start empty and you will receive
         * events for all nodes added, etc.
         */
        NORMAL,
        /**
         * rebuild() will be called before this method returns in
         * order to get an initial view of the node.
         */
        BUILD_INITIAL_CACHE,
        /**
         * After cache is primed with initial values (in the background) a
         * PathChildrenCacheEvent.Type.INITIALIZED event will be posted
         */
        POST_INITIALIZED_EVENT
    }

    fun start(buildInitial: Boolean = false) {
        start(if (buildInitial) BUILD_INITIAL_CACHE else NORMAL)
    }

    fun start(mode: StartMode) {
        semaphore.withLock {
            if (startCalled)
                throw EtcdRecipeRuntimeException("start() already called")
            checkCloseNotCalled()

            // Preload with initial data
            if (mode == BUILD_INITIAL_CACHE || mode == POST_INITIALIZED_EVENT) {
                executor.execute {
                    try {
                        loadData()
                    } finally {
                        if (mode == POST_INITIALIZED_EVENT)
                            listeners.forEach { listener ->
                                try {
                                    val cacheEvent =
                                        PathChildrenCacheEvent("", INITIALIZED, null).apply {
                                            initialDataVal = currentData
                                        }
                                    listener.childEvent(cacheEvent)
                                } catch (e: Throwable) {
                                    logger.error(e) { "Exception in cacheChanged()" }
                                    exceptionList.value += e
                                }
                            }
                        setupWatcher()
                        startThreadComplete.set(true)
                    }
                }
            } else {
                setupWatcher()
                startThreadComplete.set(true)
            }

            startCalled = true
        }
    }

    @Throws(InterruptedException::class)
    fun waitOnStartComplete(): Boolean = waitOnStartComplete(Long.MAX_VALUE.days)

    @Throws(InterruptedException::class)
    fun waitOnStartComplete(timeout: Long, timeUnit: TimeUnit): Boolean =
        waitOnStartComplete(Conversions.timeUnitToDuration(timeout, timeUnit))

    @Throws(InterruptedException::class)
    fun waitOnStartComplete(timeout: Duration): Boolean {
        checkStartCalled()
        checkCloseNotCalled()
        return startThreadComplete.waitUntilTrue(timeout)
    }

    private fun setupWatcher() {
        watchClient.watcher(cachePath, cachePath.asPrefixWatchOption) { watchResponse ->
            watchResponse.events
                .forEach { event ->
                    when (event.eventType) {
                        PUT          -> {
                            val (k, v) = event.keyValue.asPair
                            val s = k.substring(cachePath.length + 1)
                            val isAdd = !cacheMap.containsKey(s)
                            cacheMap[s] = v
                            val cacheEvent = PathChildrenCacheEvent(s, if (isAdd) CHILD_ADDED else CHILD_UPDATED, v)
                            listeners.forEach { listener ->
                                try {
                                    listener.childEvent(cacheEvent)
                                } catch (e: Throwable) {
                                    logger.error(e) { "Exception in cacheChanged()" }
                                    exceptionList.value += e
                                }
                            }
                            println("$s ${if (isAdd) "added" else "updated"}")
                        }
                        DELETE       -> {
                            val k = event.keyValue.key.asString
                            val s = k.substring(cachePath.length + 1)
                            val prevValue = cacheMap.remove(k)?.let { it }
                            val cacheEvent = PathChildrenCacheEvent(s, CHILD_REMOVED, prevValue)
                            listeners.forEach { listener ->
                                try {
                                    listener.childEvent(cacheEvent)
                                } catch (e: Throwable) {
                                    logger.error(e) { "Exception in cacheChanged()" }
                                    exceptionList.value += e
                                }
                            }
                            println("$k deleted")
                        }
                        UNRECOGNIZED -> logger.error { "Unrecognized error with $cachePath watch" }
                        else         -> logger.error { "Unknown error with $cachePath watch" }
                    }
                }
        }
    }

    private fun loadData() {
        try {
            val kvs = kvClient.getKeyValues(cachePath)
            for (kv in kvs) {
                val (k, v) = kv
                val s = k.substring(cachePath.length + 1)
                cacheMap[s] = v
            }
        } catch (e: Throwable) {
            logger.error(e) { "Exception in loadData()" }
            exceptionList.value += e
        }
    }

    fun rebuild() {
        clear()
        loadData()
    }

    val currentData: List<ChildData> = cacheMap.toSortedMap().map { (k, v) -> ChildData(k, v) }

    fun getCurrentData(path: String) = cacheMap.get(path)

    fun clear() {
        cacheMap.clear()
    }

    private fun checkStartCalled() {
        if (!startCalled) throw EtcdRecipeRuntimeException("start() not called")
    }

    override fun close() {
        closeSemaphore.withLock {
            checkStartCalled()

            startThreadComplete.waitUntilTrue()

            // Close waiter before shutting down executor
            super.close()
            if (userExecutor == null) (executor as ExecutorService).shutdown()
        }
    }

    companion object : KLogging()
}