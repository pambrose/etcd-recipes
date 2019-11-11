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

import com.google.common.collect.Maps.newConcurrentMap
import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.time.timeUnitToDuration
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
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
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.ensureSuffix
import io.etcd.recipes.common.getChildren
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import mu.KLogging
import java.io.Closeable
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

class PathChildrenCache(client: Client,
                        val cachePath: String,
                        private val userExecutor: Executor? = null) : EtcdConnector(client), Closeable {

    private var startCalled by atomicBoolean(false)
    private val startThreadComplete = BooleanMonitor(false)
    private val cacheMap: ConcurrentMap<String, ByteSequence> = newConcurrentMap()
    private val listeners: MutableList<PathChildrenCacheListener> = mutableListOf()
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

    @JvmOverloads
    fun start(buildInitial: Boolean = false, waitOnStartComplete: Boolean = true): PathChildrenCache =
        start(if (buildInitial) BUILD_INITIAL_CACHE else NORMAL, waitOnStartComplete)

    @JvmOverloads
    @Synchronized
    fun start(mode: StartMode, waitOnStartComplete: Boolean = true): PathChildrenCache {
        if (startCalled)
            throw EtcdRecipeRuntimeException("start() already called")
        checkCloseNotCalled()

        // Preload with initial data
        if (mode == BUILD_INITIAL_CACHE || mode == POST_INITIALIZED_EVENT) {
            executor.execute {
                try {
                    setupWatcher()
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
                    startThreadComplete.set(true)
                }
            }
        } else {
            setupWatcher()
            startThreadComplete.set(true)
        }

        startCalled = true

        if (waitOnStartComplete)
            waitOnStartComplete()

        return this
    }

    fun addListener(listener: PathChildrenCacheListener) {
        listeners += listener
    }

    fun addListener(block: (PathChildrenCacheEvent) -> Unit) {
        addListener(
            object : PathChildrenCacheListener {
                override fun childEvent(event: PathChildrenCacheEvent) {
                    block(event)
                }
            })
    }

    fun clearListeners() = listeners.clear()

    private fun loadData() {
        try {
            val kvs = client.getChildren(cachePath)
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

    private fun setupWatcher() {
        val trailingPath = cachePath.ensureSuffix("/")
        logger.debug { "Setting up watch for $trailingPath" }
        val watchOption = watchOption { withPrefix(trailingPath.asByteSequence) }
        client.watcher(trailingPath, watchOption) { watchResponse ->
            watchResponse.events
                .forEach { event ->
                    val (k, v) = event.keyValue.asPair
                    val stripped = k.substring(trailingPath.length)
                    when (event.eventType) {
                        PUT          -> {
                            val isAdd = !cacheMap.containsKey(stripped)
                            logger.debug { "$stripped ${if (isAdd) "added" else "updated"}" }
                            cacheMap[stripped] = v

                            val cacheEvent =
                                PathChildrenCacheEvent(stripped, if (isAdd) CHILD_ADDED else CHILD_UPDATED, v)
                            listeners.forEach { listener ->
                                try {
                                    listener.childEvent(cacheEvent)
                                } catch (e: Throwable) {
                                    logger.error(e) { "Exception in cacheChanged()" }
                                    exceptionList.value += e
                                }
                            }
                        }
                        DELETE       -> {
                            logger.debug { "$stripped deleted" }
                            val prevValue = cacheMap.remove(stripped)?.let { it }
                            val cacheEvent = PathChildrenCacheEvent(stripped, CHILD_REMOVED, prevValue)
                            listeners.forEach { listener ->
                                try {
                                    listener.childEvent(cacheEvent)
                                } catch (e: Throwable) {
                                    logger.error(e) { "Exception in cacheChanged()" }
                                    exceptionList.value += e
                                }
                            }
                        }
                        UNRECOGNIZED -> logger.error { "Unrecognized error with $cachePath watch" }
                        else         -> logger.error { "Unknown error with $cachePath watch" }
                    }
                }
        }
    }

    @Throws(InterruptedException::class)
    fun waitOnStartComplete(): Boolean = waitOnStartComplete(Long.MAX_VALUE.days)

    @Throws(InterruptedException::class)
    fun waitOnStartComplete(timeout: Long, timeUnit: TimeUnit): Boolean =
        waitOnStartComplete(timeUnitToDuration(timeout, timeUnit))

    @Throws(InterruptedException::class)
    fun waitOnStartComplete(timeout: Duration): Boolean {
        checkStartCalled()
        checkCloseNotCalled()
        return startThreadComplete.waitUntilTrue(timeout)
    }

    fun rebuild() {
        clear()
        loadData()
    }

    // For consistency with Curator
    val currentData: List<ChildData> get() = cacheMap.map { (k, v) -> ChildData(k, v) }.sortedBy { it.key }

    // For consistency with Curator
    fun getCurrentData(path: String): ByteSequence? = cacheMap[path]

    val currentDataAsMap: Map<String, ByteSequence> get() = cacheMap.toMap()


    fun clear() = cacheMap.clear()

    private fun checkStartCalled() {
        if (!startCalled) throw EtcdRecipeRuntimeException("start() not called")
    }

    @Synchronized
    override fun close() {
        if (closeCalled)
            return

        checkStartCalled()

        listeners.clear()
        startThreadComplete.waitUntilTrue()

        // Close waiter before shutting down executor
        super.close()

        if (userExecutor == null) (executor as ExecutorService).shutdown()
    }

    companion object : KLogging()
}