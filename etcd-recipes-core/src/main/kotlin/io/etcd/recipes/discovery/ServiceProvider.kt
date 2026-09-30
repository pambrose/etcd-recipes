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
import io.etcd.recipes.common.getChildren
import java.util.concurrent.ConcurrentHashMap
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

    // How many instances have a down entry (tests).
    internal val downEntryCount: Int get() = down.size

    init {
      require(serviceName.isNotEmpty()) { "ServiceProvider service name cannot be empty" }
      require(errorThreshold > 0) { "errorThreshold must be > 0" }
    }

    /** Opens the owned watch-backed cache so subsequent reads are in-memory. One-shot. */
    @Synchronized
    fun start(): ServiceProvider {
      if (startCalled.load()) throw EtcdRecipeRuntimeException("start() already called")
      checkCloseNotCalled()
      // Report the owned cache's failures and connection state as the provider's own: an
      // abandoned watch means getInstance() serves a list that is no longer updating.
      cache = ServiceCache(client, namesPath, serviceName, resilience).also { forwardHealthOf(it) }.start()
      startCalled.store(true)
      startThreadComplete.set(true)
      return this
    }

    /**
     * All registered instances: cache-backed once [start]ed, a direct GET otherwise. Either
     * way, an entry that doesn't decode is skipped and recorded in [exceptions].
     */
    fun getAllInstances(): List<ServiceInstance> =
      if (startCalled.load())
        cache?.instances ?: emptyList()
      else
        client.getChildren(instancesPath, rpc = resilience.rpc).mapNotNull { (key, value) ->
          decodeInstanceOrNull(key, value.asString) { recordException(it) }
        }

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
      if (down.isEmpty()) return all
      // Forget instances that are gone or whose entry has lapsed. computeIfPresent keeps each
      // decision atomic with a noteError() on the same instance, so a fresh ejection survives.
      val present = all.toHashSet()
      down.keys.forEach { instance ->
        down.computeIfPresent(instance) { _, entry -> entry.takeUnless { instance !in present || it.isLapsed() } }
      }
      return all.filterNot { isDown(it) }
    }

    /**
     * Records a failed request against [instance]. After [errorThreshold] errors within
     * [downPeriod] of the first, it is ejected from selection for [downPeriod], then
     * automatically becomes eligible again; errors further apart than that never add up.
     * Pass the instance returned by [getInstance] unmodified — ejection keys on its value.
     */
    fun noteError(instance: ServiceInstance) {
      down.compute(instance) { _, entry -> withError(entry) }
    }

    // [entry] after one more error.
    private fun withError(entry: DownEntry?): DownEntry {
      val now = TimeSource.Monotonic.markNow()
      val open = entry?.takeIf { it.firstErrorAt != null && !(it.firstErrorAt + downPeriod).hasPassedNow() }
      val errors = (open?.errors ?: 0) + 1
      return if (errors >= errorThreshold)
        DownEntry(errors = 0, firstErrorAt = null, downUntil = now + downPeriod)
      else
        DownEntry(errors, open?.firstErrorAt ?: now, entry?.downUntil)
    }

    private fun isDown(instance: ServiceInstance): Boolean = down[instance]?.downUntil?.hasPassedNow() == false

    // Neither ejected nor counting errors: the entry no longer means anything.
    private fun DownEntry.isLapsed(): Boolean =
      (downUntil == null || downUntil.hasPassedNow()) &&
        (firstErrorAt == null || (firstErrorAt + downPeriod).hasPassedNow())

    @Synchronized
    override fun doClose() {
      // No checkStartCalled(): an un-started provider must close as a no-op.
      cache?.close()
      cache = null
      down.clear()
    }

    // Immutable: replaced whole, through the map's per-key compute functions.
    private class DownEntry(
      val errors: Int,
      val firstErrorAt: TimeSource.Monotonic.ValueTimeMark?,
      val downUntil: TimeSource.Monotonic.ValueTimeMark?,
    )

    companion object {
      const val DEFAULT_ERROR_THRESHOLD = 3
      val DEFAULT_DOWN_PERIOD: Duration = 30.seconds
    }
  }
