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
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.asString
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
import kotlin.time.TimeSource

@JvmOverloads
fun <T> withPathChildrenCache(
  client: Client,
  cachePath: String,
  userExecutor: Executor? = null,
  receiver: PathChildrenCache.() -> T,
): T = PathChildrenCache(client, cachePath, userExecutor).use { it.receiver() }

class PathChildrenCache
  @JvmOverloads
  constructor(
    client: Client,
    val cachePath: String,
    private val userExecutor: Executor? = null,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : EtcdConnector(client, resilience) {
  // Canonical prefix for the watched key range. Every child key is stripped
  // relative to this, never cachePath.length + 1: when cachePath already ends in
  // '/', that offset over-strips by one char and corrupts every child name, so the
  // snapshot/rebuild paths would disagree with the live watcher (which already
  // strips by trailingPath.length). One field keeps all three sites in lockstep.
  private val trailingPath = cachePath.ensureSuffix("/")

  // Written by the start worker (primed modes) or start() itself, read by doClose().
  @Volatile
  private var watcher: Watch.Watcher? = null
  private val cacheMap: ConcurrentMap<String, ByteSequence> = newConcurrentMap()
  private val listeners: MutableList<PathChildrenCacheListener> = CopyOnWriteArrayList()
  private val recoveryListeners: MutableList<WatchRecoveryListener> = CopyOnWriteArrayList()

  // Use a single-threaded executor to maintain order
  private val executor = userExecutor ?: Executors.newSingleThreadExecutor()

  // Serializes applying watch events with applying a snapshot, and tracks the newest etcd
  // revision the map reflects, so a snapshot older than an event the watch already
  // applied is never applied over it (which could resurrect a deleted child for good).
  private val applyLock = Any()
  private var lastAppliedRevision = 0L // guarded by applyLock

  // Why a primed start failed to load; surfaced by waitOnStartComplete() and start().
  @Volatile
  private var startFailure: Throwable? = null

  // The primed start's worker thread while it runs, so a close() from an INITIALIZED
  // listener (which runs on it) doesn't wait on itself.
  @Volatile
  private var loaderThread: Thread? = null

  override val exceptionContext get() = "PathChildrenCache[$cachePath]"

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

  /**
   * Starts the cache. In a primed mode ([StartMode.BUILD_INITIAL_CACHE] or
   * [StartMode.POST_INITIALIZED_EVENT]) the snapshot loads on the executor; when it can't
   * load, the cache never watches, reports [io.etcd.recipes.common.ConnectionState.LOST],
   * and fires no INITIALIZED. With [waitOnStartComplete], that failure is thrown here.
   */
  @JvmOverloads
  fun start(
    mode: StartMode,
    waitOnStartComplete: Boolean = true,
  ): PathChildrenCache {
    synchronized(this) {
      if (startCalled.load())
        throw EtcdRecipeRuntimeException("start() already called")
      checkCloseNotCalled()
      startCalled.store(true)

      if (mode == StartMode.BUILD_INITIAL_CACHE || mode == StartMode.POST_INITIALIZED_EVENT) {
        executor.execute {
          withRecipeLoggingContext { loadDataAndStartWatcher(mode == StartMode.POST_INITIALIZED_EVENT) }
        }
      } else {
        // NORMAL mode: no snapshot, just start watching from now.
        setupWatcher(0L)
        startThreadComplete.set(true)
      }
    }

    // Wait outside the monitor: an INITIALIZED listener may call rebuild(), clear(), or close()
    if (waitOnStartComplete)
      waitOnStartComplete()

    return this
  }

  fun addListener(listener: PathChildrenCacheListener) {
    listeners += listener
  }

  fun removeListener(listener: PathChildrenCacheListener) {
    listeners -= listener
  }

  /**
   * Registers a listener for watch-recovery events (resubscribes after fatal stream
   * deaths, compaction resyncs, abandoned recovery).
   */
  fun addRecoveryListener(listener: WatchRecoveryListener) {
    recoveryListeners += listener
  }

  fun removeRecoveryListener(listener: WatchRecoveryListener) {
    recoveryListeners -= listener
  }

  fun clearListeners() = listeners.clear()

  // Snapshot, then watch anchored just past the snapshot's revision: the watcher receives
  // every event after the snapshot, with no overlap and no gap. INITIALIZED fires between
  // the two, carrying the snapshot, so it precedes every event the watch delivers.
  @Suppress("TooGenericExceptionCaught")
  private fun loadDataAndStartWatcher(postInitialized: Boolean) {
    loaderThread = Thread.currentThread()
    try {
      val start = TimeSource.Monotonic.markNow()
      val resp = client.getResponse(trailingPath, childrenOption(), resilience.rpc)
      val initial = resp.kvs.map { kv -> ChildData(kv.key.asString.substring(trailingPath.length), kv.value) }
      synchronized(applyLock) {
        initial.forEach { child -> cacheMap[child.key] = child.value }
        lastAppliedRevision = resp.header.revision
      }
      resilience.metrics.recordCacheSync(cachePath, start.elapsedNow(), initial.size)

      if (postInitialized) fireInitialized(initial)
      // A close() from an INITIALIZED listener (or another thread) means no watch at all
      if (!closeCalled.load()) setupWatcher(resp.header.revision + 1)
    } catch (e: Throwable) {
      logger.error(e) { "Priming $cachePath failed; the cache will not update" }
      startFailure = e
      recordException(e)
      reportConnectionLost()
    } finally {
      loaderThread = null
      startThreadComplete.set(true)
    }
  }

  // One immutable snapshot, shared by every listener's INITIALIZED event.
  private fun fireInitialized(initial: List<ChildData>) {
    val event = PathChildrenCacheEvent("", PathChildrenCacheEvent.Type.INITIALIZED, null).apply {
      initialDataVal = initial
    }
    fireChildEvent(event)
  }

  @Suppress("TooGenericExceptionCaught")
  private fun fireChildEvent(event: PathChildrenCacheEvent) {
    listeners.forEach { listener ->
      try {
        listener.childEvent(event)
      } catch (e: Throwable) {
        logger.error(e) { "Exception in cacheChanged()" }
        recordException(e)
      }
    }
  }

  private fun childrenOption() =
    getOption {
      isPrefix(true)
      withSortField(GetOption.SortTarget.KEY)
    }

  private fun setupWatcher(startRevision: Long) {
    logger.debug { "Setting up watch for $trailingPath at rev $startRevision" }
    val watchOption = watchOption {
      isPrefix(true).also { if (startRevision > 0L) it.withRevision(startRevision) }
    }
    watcher = client.watcher(
      trailingPath,
      watchOption,
      resilience.watch,
      recoveryListener = { event -> onRecoveryEvent(event) },
      resyncWith = { reconcile(emitEvents = true) },
    ) { watchResponse ->
      watchResponse.events
        .forEach { event ->
          val (k, v) = event.keyValue.asPair
          val stripped = k.substring(trailingPath.length)
          when (event.eventType) {
            PUT -> {
              val isAdd =
                synchronized(applyLock) {
                  lastAppliedRevision = maxOf(lastAppliedRevision, event.keyValue.modRevision)
                  cacheMap.put(stripped, v) == null
                }
              logger.debug { "$stripped ${if (isAdd) "added" else "updated"}" }
              fireChildEvent(PathChildrenCacheEvent(stripped, if (isAdd) CHILD_ADDED else CHILD_UPDATED, v))
            }

            DELETE -> {
              logger.debug { "$stripped deleted" }
              val prevValue =
                synchronized(applyLock) {
                  lastAppliedRevision = maxOf(lastAppliedRevision, event.keyValue.modRevision)
                  cacheMap.remove(stripped)
                }
              fireChildEvent(PathChildrenCacheEvent(stripped, CHILD_REMOVED, prevValue))
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

  /**
   * Waits for a primed start to finish loading. Throws [EtcdRecipeRuntimeException] when
   * the load failed, since the cache then never updates.
   */
  @Throws(InterruptedException::class)
  fun waitOnStartComplete(timeout: Duration): Boolean {
    checkStartCalled()
    checkCloseNotCalled()
    val completed = startThreadComplete.waitUntilTrueWithInterruption(timeout)
    startFailure?.let { cause -> throw EtcdRecipeRuntimeException("Priming $cachePath failed", cause) }
    return completed
  }

  /**
   * Re-syncs the cache to etcd's current children without firing events. The live map is
   * reconciled in place (drop keys no longer present, upsert the rest), so `currentData`
   * never passes through an empty or partial state. A snapshot older than a watch event
   * already applied is re-read rather than applied, so a concurrent event is never undone.
   */
  fun rebuild() {
    reconcile(emitEvents = false)
  }

  // Snapshot etcd's current children, reconcile the live map in place, and return the
  // next watch anchor (snapshot revision + 1). Runs on the caller's thread for rebuild()
  // and on the watch dispatcher for a compaction resync, so it takes applyLock rather than
  // the cache monitor (which a concurrent close() may hold). A resync emits the gap's
  // changes as events, since no watch event will ever report them.
  private fun reconcile(emitEvents: Boolean): Long {
    repeat(MAX_SNAPSHOT_ATTEMPTS) {
      val start = TimeSource.Monotonic.markNow()
      val resp = client.getResponse(trailingPath, childrenOption(), resilience.rpc)
      val snapshotRevision = resp.header.revision
      val fresh = resp.kvs.associate { kv -> kv.key.asString.substring(trailingPath.length) to kv.value }
      val changes =
        synchronized(applyLock) {
          // A watch event newer than this snapshot was applied meanwhile: re-read
          if (lastAppliedRevision > snapshotRevision) return@repeat
          val diff = if (emitEvents) changesTo(fresh) else emptyList()
          cacheMap.keys.retainAll(fresh.keys)
          cacheMap.putAll(fresh)
          lastAppliedRevision = snapshotRevision
          diff
        }
      resilience.metrics.recordCacheSync(cachePath, start.elapsedNow(), fresh.size)
      changes.forEach { fireChildEvent(it) }
      return snapshotRevision + 1
    }
    throw EtcdRecipeRuntimeException("Could not snapshot $cachePath at or past its applied watch events")
  }

  // The events that turn the current map into [fresh]: removals, then additions and updates.
  private fun changesTo(fresh: Map<String, ByteSequence>): List<PathChildrenCacheEvent> =
    cacheMap.filterKeys { it !in fresh }.toSortedMap().map { (k, v) -> PathChildrenCacheEvent(k, CHILD_REMOVED, v) } +
      fresh.mapNotNull { (k, v) ->
        when (cacheMap[k]) {
          null -> PathChildrenCacheEvent(k, CHILD_ADDED, v)

          v -> null

          // unchanged
          else -> PathChildrenCacheEvent(k, CHILD_UPDATED, v)
        }
      }

  @Suppress("TooGenericExceptionCaught")
  private fun onRecoveryEvent(event: WatchRecoveryEvent) {
    reportRecoveryEvent(event)
    if (event is WatchRecoveryEvent.Failed) {
      recordException(
        event.cause
          ?: EtcdRecipeRuntimeException("Watch on $cachePath abandoned; cache is no longer updating"),
      )
    }
    recoveryListeners.forEach { listener ->
      try {
        listener.onRecoveryEvent(event)
      } catch (e: Throwable) {
        logger.error(e) { "Exception in recovery listener" }
        recordException(e)
      }
    }
  }

  // For consistency with Curator
  val currentData: List<ChildData> get() = cacheMap.map { (k, v) -> ChildData(k, v) }.sortedBy { it.key }

  // For consistency with Curator

  /**
   * Returns the cached value for [childName], the child name relative to `cachePath` (the same keys
   * exposed by [currentDataAsMap]) — NOT a full path. Passing a full path such as `cachePath/k1`
   * returns `null`.
   */
  fun getCurrentData(childName: String): ByteSequence? = cacheMap[childName]

  val currentDataAsMap: Map<String, ByteSequence> get() = cacheMap.toMap()

  fun clear() = synchronized(applyLock) { cacheMap.clear() }

  @Synchronized
  override fun doClose() {
    checkStartCalled()

    // Wait for the background loader before touching the watcher: in
    // BUILD_INITIAL_CACHE / POST_INITIALIZED_EVENT modes the watcher is
    // assigned inside loadDataAndStartWatcher() running on `executor`. If
    // close() runs before that task assigns `watcher`, closing here first
    // would no-op on a null reference and the later-assigned watcher (and
    // its dispatcher executor) would leak. A close() from an INITIALIZED
    // listener runs on the loader itself, which then skips the watch.
    if (Thread.currentThread() !== loaderThread)
      startThreadComplete.waitUntilTrue()

    watcher?.close()
    watcher = null

    listeners.clear()

    if (userExecutor == null) (executor as ExecutorService).shutdown()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val MAX_SNAPSHOT_ATTEMPTS = 10
  }
}
