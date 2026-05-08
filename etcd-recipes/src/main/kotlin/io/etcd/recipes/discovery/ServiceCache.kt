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
import com.pambrose.common.concurrent.BooleanMonitor
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.watch.WatchEvent.EventType.*
import io.etcd.recipes.common.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicReference

class ServiceCache internal constructor(
  client: Client,
  val namesPath: String,
  val serviceName: String,
) : EtcdConnector(client) {
  private var watcher: AtomicReference<Watch.Watcher?> = AtomicReference(null)
  private var dataPreloaded = BooleanMonitor(false)
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
    val watchOption = watchOption { isPrefix(true) }

    watcher.store(
      client.watcher(trailingServicePath, watchOption) { watchResponse ->
        // Wait for data to be loaded
        dataPreloaded.waitUntilTrue()

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
                // logger.info {"$k $v ${if (newKey) "added" else "updated"}")
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
                // logger.info {"$stripped deleted")
              }

              UNRECOGNIZED -> logger.error { "Unrecognized error with $servicePath watch" }
              else -> logger.error { "Unknown error with $servicePath watch" }
            }
          }
      },
    )

    // Preload with initial data
    val kvs = client.getChildren(trailingServicePath)

    for ((k, v) in kvs) {
      val stripped = k.substring(trailingNamesPath.length)
      serviceMap[stripped] = v.asString
    }

    dataPreloaded.set(true)
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
  override fun close() {
    if (closeCalled.load())
      return

    checkStartCalled()

    watcher.load()?.close()

    listeners.clear()
    startThreadComplete.waitUntilTrue()

    super.close()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
