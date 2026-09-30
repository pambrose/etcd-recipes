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

package io.etcd.recipes.discovery

import com.google.common.collect.Maps.newConcurrentMap
import com.pambrose.common.util.ensureSuffix
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TimeSource

class ServiceCache
  @JvmOverloads
  constructor(
    client: Client,
    val namesPath: String,
    val serviceName: String,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : EtcdConnector(client, resilience) {
  // Plain var: all reads/writes are inside @Synchronized methods on this instance.
  private var watcher: Watch.Watcher? = null
  private val servicePath = namesPath.appendToPath(serviceName)

  // Decoded once, on arrival; an entry that doesn't decode is reported and left out.
  private val serviceMap: ConcurrentMap<String, ServiceInstance> = newConcurrentMap()
  private val listeners: MutableList<ServiceCacheListener> = CopyOnWriteArrayList()
  private val recoveryListeners: MutableList<WatchRecoveryListener> = CopyOnWriteArrayList()

  init {
    require(serviceName.isNotEmpty()) { "ServiceCache service name cannot be empty" }
  }

  override val exceptionContext get() = "ServiceCache[$serviceName]"

  @Synchronized
  @Suppress("TooGenericExceptionCaught", "LongMethod")
  fun start(): ServiceCache {
    if (startCalled.load())
      throw EtcdRecipeRuntimeException("start() already called")
    checkCloseNotCalled()

    val trailingServicePath = servicePath.ensureSuffix("/")
    val trailingNamesPath = namesPath.ensureSuffix("/")

    // Snapshot first, capturing the etcd revision at which the response was
    // produced. Then start the watch anchored at revision+1 so the watch
    // stream has zero overlap and zero gap relative to the snapshot — this
    // is the standard etcd pattern for consistent cache initialization.
    val anchorRevision = reconcile(emitEvents = false)

    val watchOption = watchOption {
      isPrefix(true)
      withRevision(anchorRevision)
    }

    watcher = client.watcher(
      trailingServicePath,
      watchOption,
      resilience.watch,
      recoveryListener = { event -> onRecoveryEvent(event) },
      resyncWith = { reconcile(emitEvents = true) },
    ) { watchResponse ->
      watchResponse.events
        .forEach { event ->
          val (k, v) = event.keyValue.asPair.asString
          val stripped = k.substring(trailingNamesPath.length)
          when (event.eventType) {
            WatchEvent.EventType.PUT -> {
              val instance = decodeInstanceOrNull(k, v) { recordException(it) }
              if (instance == null) {
                // No longer a usable instance: whatever the key held is gone, as on a delete
                removeInstance(stripped)
              } else {
                val isAdd = serviceMap.put(stripped, instance) == null
                notifyListeners(WatchEvent.EventType.PUT, isAdd, stripped, instance)
              }
            }

            WatchEvent.EventType.DELETE -> {
              removeInstance(stripped)
            }

            WatchEvent.EventType.UNRECOGNIZED -> {
              logger.error { "Unrecognized error with $servicePath watch" }
            }

            else -> {
              logger.error { "Unknown error with $servicePath watch" }
            }
          }
        }
    }

    startCalled.store(true)
    startThreadComplete.set(true)

    return this
  }

  // Drops the instance at [instanceKey], telling listeners only if they were told it existed.
  private fun removeInstance(instanceKey: String) {
    serviceMap.remove(instanceKey)?.let { notifyListeners(WatchEvent.EventType.DELETE, false, instanceKey, it) }
  }

  // Tells every listener about one change. [instance] is the new value, or the previous
  // value on a DELETE.
  @Suppress("TooGenericExceptionCaught")
  private fun notifyListeners(
    type: WatchEvent.EventType,
    isAdd: Boolean,
    instanceKey: String,
    instance: ServiceInstance?,
  ) {
    listeners.forEach { listener ->
      try {
        listener.cacheChanged(type, isAdd, instanceKey, instance)
      } catch (e: Throwable) {
        logger.error(e) { "Exception in cacheChanged()" }
        recordException(e)
      }
    }
  }

  // Snapshot etcd's current instances, reconcile the live map in place (drop keys no
  // longer present, upsert the rest), and return the next watch anchor (snapshot
  // revision + 1). Runs during start() and, on the watch dispatcher thread, during
  // compaction resync — deliberately not synchronized (see PathChildrenCache.reconcile).
  // A resync reports the gap's changes to listeners, since no watch event ever will.
  private fun reconcile(emitEvents: Boolean): Long {
    val start = TimeSource.Monotonic.markNow()
    val trailingServicePath = servicePath.ensureSuffix("/")
    val trailingNamesPath = namesPath.ensureSuffix("/")
    val getOption = getOption {
      isPrefix(true)
      withSortField(GetOption.SortTarget.KEY)
    }
    val resp = client.getResponse(trailingServicePath, getOption, resilience.rpc)
    val fresh = LinkedHashMap<String, ServiceInstance>()
    resp.kvs.forEach { kv ->
      val key = kv.key.asString
      decodeInstanceOrNull(key, kv.value.asString) { recordException(it) }
        ?.let { fresh[key.substring(trailingNamesPath.length)] = it }
    }
    val removed = serviceMap.filterKeys { it !in fresh }
    val changed = fresh.filter { (instanceKey, instance) -> serviceMap[instanceKey] != instance }
    val added = changed.keys.filterNot { it in serviceMap }.toSet()
    serviceMap.keys.retainAll(fresh.keys)
    serviceMap.putAll(fresh)
    resilience.metrics.recordCacheSync(servicePath, start.elapsedNow(), serviceMap.size)
    if (emitEvents) {
      removed.forEach { (key, instance) -> notifyListeners(WatchEvent.EventType.DELETE, false, key, instance) }
      changed.forEach { (key, instance) -> notifyListeners(WatchEvent.EventType.PUT, key in added, key, instance) }
    }
    return resp.header.revision + 1
  }

  @Suppress("TooGenericExceptionCaught")
  private fun onRecoveryEvent(event: WatchRecoveryEvent) {
    reportRecoveryEvent(event)
    if (event is WatchRecoveryEvent.Failed) {
      recordException(
        event.cause
          ?: EtcdRecipeRuntimeException("Watch on $servicePath abandoned; service cache is no longer updating"),
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

  val instances: List<ServiceInstance>
    get() {
      checkCloseNotCalled()
      return serviceMap.values.toList()
    }

  fun addListenerForChanges(listener: ServiceCacheListener) {
    checkCloseNotCalled()
    listeners += listener
  }

  fun removeListenerForChanges(listener: ServiceCacheListener) {
    listeners -= listener
  }

  /**
   * Registers a listener for watch-recovery events (resubscribes after fatal stream
   * deaths, compaction resyncs, abandoned recovery).
   */
  fun addRecoveryListener(listener: WatchRecoveryListener) {
    checkCloseNotCalled()
    recoveryListeners += listener
  }

  fun removeRecoveryListener(listener: WatchRecoveryListener) {
    recoveryListeners -= listener
  }

  @Synchronized
  override fun doClose() {
    // No checkStartCalled(): an un-started cache closes as a no-op, as a ServiceProvider does
    if (!startCalled.load()) return

    watcher?.close()
    watcher = null

    listeners.clear()
    startThreadComplete.waitUntilTrue()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
