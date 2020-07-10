/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.concurrent.BooleanMonitor
import com.github.pambrose.common.delegate.AtomicDelegates
import com.google.common.collect.Maps.newConcurrentMap
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.ensureSuffix
import io.etcd.recipes.common.getChildren
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.etcd.recipes.common.withPrefix
import mu.KLogging
import java.util.Collections.synchronizedList
import java.util.concurrent.ConcurrentMap

class ServiceCache internal constructor(client: Client,
                                        val namesPath: String,
                                        val serviceName: String) : EtcdConnector(client) {

  private var watcher: Watch.Watcher? by AtomicDelegates.nullableReference()
  private var dataPreloaded = BooleanMonitor(false)
  private val servicePath = namesPath.appendToPath(serviceName)
  private val serviceMap: ConcurrentMap<String, String> = newConcurrentMap()
  private val listeners: MutableList<ServiceCacheListener> = synchronizedList(mutableListOf())

  init {
    require(serviceName.isNotEmpty()) { "ServiceCache service name cannot be empty" }
  }

  @Synchronized
  fun start(): ServiceCache {
    if (startCalled)
      throw EtcdRecipeRuntimeException("start() already called")
    checkCloseNotCalled()

    val trailingServicePath = servicePath.ensureSuffix("/")
    val trailingNamesPath = namesPath.ensureSuffix("/")
    val watchOption = watchOption { withPrefix(trailingServicePath) }

    watcher = client.watcher(trailingServicePath, watchOption) { watchResponse ->
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
              //println("$k $v ${if (newKey) "added" else "updated"}")
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
              //println("$stripped deleted")
            }
            UNRECOGNIZED -> logger.error { "Unrecognized error with $servicePath watch" }
            else -> logger.error { "Unknown error with $servicePath watch" }
          }
        }
      startThreadComplete.set(true)
    }

    // Preload with initial data
    val kvs = client.getChildren(trailingServicePath)

    for ((k, v) in kvs) {
      val stripped = k.substring(trailingNamesPath.length)
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

  @Synchronized
  override fun close() {
    if (closeCalled)
      return

    checkStartCalled()

    watcher?.close()

    listeners.clear()
    startThreadComplete.waitUntilTrue()

    super.close()
  }

  companion object : KLogging()
}