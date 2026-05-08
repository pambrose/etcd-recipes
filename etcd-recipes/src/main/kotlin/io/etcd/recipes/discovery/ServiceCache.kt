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
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.watch.WatchEvent.EventType.*
import io.etcd.recipes.common.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList

class ServiceCache(
  client: Client,
  val namesPath: String,
  val serviceName: String,
) : EtcdConnector(client) {
  // Plain var: all reads/writes are inside @Synchronized methods on this instance.
  private var watcher: Watch.Watcher? = null
  private val servicePath = namesPath.appendToPath(serviceName)
  private val serviceMap: ConcurrentMap<String, String> = newConcurrentMap()
  private val listeners: MutableList<ServiceCacheListener> = CopyOnWriteArrayList()

  init {
    require(serviceName.isNotEmpty()) { "ServiceCache service name cannot be empty" }
  }

  @Synchronized
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
    val getOption = getOption {
      isPrefix(true)
      withSortField(GetOption.SortTarget.KEY)
    }
    val resp = client.getResponse(trailingServicePath, getOption)
    for (kv in resp.kvs) {
      val k = kv.key.asString
      val stripped = k.substring(trailingNamesPath.length)
      serviceMap[stripped] = kv.value.asString
    }
    val anchorRevision = resp.header.revision + 1

    val watchOption = watchOption {
      isPrefix(true)
      withRevision(anchorRevision)
    }

    watcher = client.watcher(trailingServicePath, watchOption) { watchResponse ->
      watchResponse.events
        .forEach { event ->
          val (k, v) = event.keyValue.asPair.asString
          val stripped = k.substring(trailingNamesPath.length)
          when (event.eventType) {
            PUT -> {
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
            }

            DELETE -> {
              val prevValue = serviceMap.remove(stripped)?.let { ServiceInstance.toObject(it) }
              listeners.forEach { listener ->
                try {
                  listener.cacheChanged(DELETE, false, stripped, prevValue)
                } catch (e: Throwable) {
                  logger.error(e) { "Exception in cacheChanged()" }
                  exceptionList.value += e
                }
              }
            }

            UNRECOGNIZED -> {
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

  val instances: List<ServiceInstance>
    get() {
      checkCloseNotCalled()
      return serviceMap.values.map { ServiceInstance.toObject(it) }
    }

  fun addListenerForChanges(listener: ServiceCacheListener) {
    checkCloseNotCalled()
    listeners += listener
  }

  @Synchronized
  override fun doClose() {
    checkStartCalled()

    watcher?.close()
    watcher = null

    listeners.clear()
    startThreadComplete.waitUntilTrue()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
