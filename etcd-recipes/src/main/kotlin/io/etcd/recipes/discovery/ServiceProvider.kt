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

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getChildrenValues
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Client-side load balancer over a service's instances. Selection is pluggable
 * ([ProviderStrategy]; [RandomStrategy] by default) and failing instances can be ejected
 * with [noteError].
 *
 * Two read modes:
 * - after [start], reads are served from an owned, watch-updated [ServiceCache] — cheap
 *   and current, the mode to use on a hot path;
 * - before [start] (or without ever starting), each read does a direct etcd range GET —
 *   the original behavior, kept for callers that just want a one-off lookup.
 *
 * Stateful strategies ([RoundRobinStrategy], [StickyStrategy]) must not be shared across
 * providers. [close] releases the owned cache (if started) and is a safe no-op otherwise.
 */
class ServiceProvider
  @JvmOverloads
  constructor(
    client: Client,
    private val namesPath: String,
    val serviceName: String,
    private val strategy: ProviderStrategy = RandomStrategy,
    private val errorThreshold: Int = DEFAULT_ERROR_THRESHOLD,
    private val downPeriod: Duration = DEFAULT_DOWN_PERIOD,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : EtcdConnector(client, resilience) {
    // Direct path to the service's instances (no path doubling — mirrors ServiceCache).
    private val instancesPath: String = namesPath.appendToPath(serviceName)

    // Owned, lazily-started cache; non-null only between start() and doClose(). Plain var
    // guarded by @Synchronized start()/doClose(), matching ServiceCache's `watcher` style.
    private var cache: ServiceCache? = null

    // Ejected instances, keyed by ServiceInstance value-equality (id is not stable).
    private val down = ConcurrentHashMap<ServiceInstance, DownEntry>()

    init {
      require(serviceName.isNotEmpty()) { "ServiceProvider service name cannot be empty" }
      require(errorThreshold > 0) { "errorThreshold must be > 0" }
    }

    /** Opens the owned watch-backed cache so subsequent reads are in-memory. One-shot. */
    @Synchronized
    fun start(): ServiceProvider {
      if (startCalled.load()) throw EtcdRecipeRuntimeException("start() already called")
      checkCloseNotCalled()
      cache = ServiceCache(client, namesPath, serviceName, resilience).start()
      startCalled.store(true)
      startThreadComplete.set(true)
      return this
    }

    /** All registered instances: cache-backed once [start]ed, a direct GET otherwise. */
    fun getAllInstances(): List<ServiceInstance> =
      if (startCalled.load())
        cache?.instances ?: emptyList()
      else
        client.getChildrenValues(instancesPath).map { ServiceInstance.toObject(it.asString) }

    /**
     * Selects one available instance via the configured [strategy]. Throws the typed
     * [EtcdRecipeException] (naming the service) when nothing is available — whether none
     * are registered or all have been ejected by [noteError].
     */
    @Throws(EtcdRecipeException::class)
    fun getInstance(): ServiceInstance =
      strategy.select(availableInstances())
        ?: throw EtcdRecipeException("No instances available for service $serviceName")

    // getAllInstances() minus instances still inside their down window.
    private fun availableInstances(): List<ServiceInstance> {
      val all = getAllInstances()
      return if (down.isEmpty()) all else all.filterNot { isDown(it) }
    }

    /**
     * Records a failed request against [instance]. After [errorThreshold] errors it is
     * ejected from selection for [downPeriod], then automatically becomes eligible again.
     * Pass the instance returned by [getInstance] unmodified — ejection keys on its value.
     */
    fun noteError(instance: ServiceInstance) {
      val entry = down.computeIfAbsent(instance) { DownEntry() }
      if (entry.errors.incrementAndFetch() >= errorThreshold) {
        entry.downUntil = TimeSource.Monotonic.markNow() + downPeriod
        entry.errors.store(0)
      }
    }

    @Suppress("ReturnCount")
    private fun isDown(instance: ServiceInstance): Boolean {
      val entry = down[instance] ?: return false
      val until = entry.downUntil ?: return false
      if (until.hasPassedNow()) {
        down.remove(instance) // lazy cleanup on auto-recovery
        return false
      }
      return true
    }

    @Synchronized
    override fun doClose() {
      // No checkStartCalled(): an un-started provider must close as a no-op.
      cache?.close()
      cache = null
      down.clear()
    }

    private class DownEntry {
      val errors = AtomicInt(0)

      @Volatile
      var downUntil: TimeSource.Monotonic.ValueTimeMark? = null
    }

    companion object {
      const val DEFAULT_ERROR_THRESHOLD = 3
      val DEFAULT_DOWN_PERIOD: Duration = 30.seconds
    }
  }
