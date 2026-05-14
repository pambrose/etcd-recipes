/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
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
import com.pambrose.common.time.timeUnitToDuration
import com.pambrose.common.util.ensureSuffix
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_ADDED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_REMOVED
import io.etcd.recipes.cache.PathChildrenCacheEvent.Type.CHILD_UPDATED
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getKeyValuePairs
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@JvmOverloads
fun <T> withPathChildrenCache(
  client: Client,
  cachePath: String,
  userExecutor: Executor? = null,
  receiver: PathChildrenCache.() -> T,
): T = PathChildrenCache(client, cachePath, userExecutor).use { it.receiver() }

class PathChildrenCache(
  client: Client,
  val cachePath: String,
  private val userExecutor: Executor? = null,
) : EtcdConnector(client) {
  // Plain var: all reads/writes are inside @Synchronized methods on this instance.
  private var watcher: Watch.Watcher? = null
  private val cacheMap: ConcurrentMap<String, ByteSequence> = newConcurrentMap()
  private val listeners: MutableList<PathChildrenCacheListener> = CopyOnWriteArrayList()

  // Use a single-threaded executor to maintain order
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
    POST_INITIALIZED_EVENT,
  }

  @JvmOverloads
  fun start(
    buildInitial: Boolean = false,
    waitOnStartComplete: Boolean = true,
  ): PathChildrenCache =
    start(if (buildInitial) StartMode.BUILD_INITIAL_CACHE else StartMode.NORMAL, waitOnStartComplete)

  @Suppress("TooGenericExceptionCaught")
  @JvmOverloads
  @Synchronized
  fun start(
    mode: StartMode,
    waitOnStartComplete: Boolean = true,
  ): PathChildrenCache {
    if (startCalled.load())
      throw EtcdRecipeRuntimeException("start() already called")
    checkCloseNotCalled()

    if (mode == StartMode.BUILD_INITIAL_CACHE || mode == StartMode.POST_INITIALIZED_EVENT) {
      executor.execute {
        try {
          // Snapshot then watch with the snapshot's revision as the watch
          // anchor: the watcher receives every event that occurred after the
          // snapshot revision, with no overlap and no gap. Without anchoring
          // a PUT could land between watch-registration and snapshot-load,
          // and the snapshot would silently overwrite the newer value.
          loadDataAndStartWatcher()
        } finally {
          if (mode == StartMode.POST_INITIALIZED_EVENT)
            listeners.forEach { listener ->
              try {
                val cacheEvent =
                  PathChildrenCacheEvent("", PathChildrenCacheEvent.Type.INITIALIZED, null).apply {
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
      // NORMAL mode: no snapshot, just start watching from now.
      setupWatcher(0L)
      startThreadComplete.set(true)
    }

    startCalled.store(true)

    if (waitOnStartComplete)
      waitOnStartComplete()

    return this
  }

  fun addListener(listener: PathChildrenCacheListener) {
    listeners += listener
  }

  fun clearListeners() = listeners.clear()

  @Suppress("TooGenericExceptionCaught")
  private fun loadDataAndStartWatcher() {
    try {
      val trailingPath = cachePath.ensureSuffix("/")
      val getOption = getOption {
        isPrefix(true)
        withSortField(GetOption.SortTarget.KEY)
      }
      val resp = client.getResponse(trailingPath, getOption)
      val anchorRevision = resp.header.revision + 1

      for (kv in resp.kvs) {
        val k = kv.key.asString
        val s = k.substring(cachePath.length + 1)
        cacheMap[s] = kv.value
      }

      setupWatcher(anchorRevision)
    } catch (e: Throwable) {
      logger.error(e) { "Exception in loadDataAndStartWatcher()" }
      exceptionList.value += e
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun setupWatcher(startRevision: Long) {
    val trailingPath = cachePath.ensureSuffix("/")
    logger.debug { "Setting up watch for $trailingPath at rev $startRevision" }
    val watchOption = watchOption {
      isPrefix(true).also { if (startRevision > 0L) it.withRevision(startRevision) }
    }
    watcher = client.watcher(trailingPath, watchOption) { watchResponse ->
      watchResponse.events
        .forEach { event ->
          val (k, v) = event.keyValue.asPair
          val stripped = k.substring(trailingPath.length)
          when (event.eventType) {
            PUT -> {
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

            DELETE -> {
              logger.debug { "$stripped deleted" }
              val prevValue = cacheMap.remove(stripped)
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

            UNRECOGNIZED -> {
              logger.error { "Unrecognized error with $cachePath watch" }
            }

            else -> {
              logger.error { "Unknown error with $cachePath watch" }
            }
          }
        }
    }
  }

  @Throws(InterruptedException::class)
  fun waitOnStartComplete(): Boolean = waitOnStartComplete(Long.MAX_VALUE.days)

  @Throws(InterruptedException::class)
  fun waitOnStartComplete(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = waitOnStartComplete(timeUnitToDuration(timeout, timeUnit))

  @Throws(InterruptedException::class)
  fun waitOnStartComplete(timeout: Duration): Boolean {
    checkStartCalled()
    checkCloseNotCalled()
    return startThreadComplete.waitUntilTrue(timeout)
  }

  fun rebuild() {
    clear()
    val trailingPath = cachePath.ensureSuffix("/")
    val getOption = getOption {
      isPrefix(true)
      withSortField(GetOption.SortTarget.KEY)
    }
    for ((k, v) in client.getKeyValuePairs(trailingPath, getOption)) {
      val s = k.substring(cachePath.length + 1)
      cacheMap[s] = v
    }
  }

  // For consistency with Curator
  val currentData: List<ChildData> get() = cacheMap.map { (k, v) -> ChildData(k, v) }.sortedBy { it.key }

  // For consistency with Curator
  fun getCurrentData(path: String): ByteSequence? = cacheMap[path]

  val currentDataAsMap: Map<String, ByteSequence> get() = cacheMap.toMap()

  fun clear() = cacheMap.clear()

  @Synchronized
  override fun doClose() {
    checkStartCalled()

    // Wait for the background loader before touching the watcher: in
    // BUILD_INITIAL_CACHE / POST_INITIALIZED_EVENT modes the watcher is
    // assigned inside loadDataAndStartWatcher() running on `executor`. If
    // close() runs before that task assigns `watcher`, closing here first
    // would no-op on a null reference and the later-assigned watcher (and
    // its dispatcher executor) would leak.
    startThreadComplete.waitUntilTrue()

    watcher?.close()
    watcher = null

    listeners.clear()

    if (userExecutor == null) (executor as ExecutorService).shutdown()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
