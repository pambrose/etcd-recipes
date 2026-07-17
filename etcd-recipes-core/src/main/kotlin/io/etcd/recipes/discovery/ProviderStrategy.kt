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

@file:Suppress("MatchingDeclarationName")

package io.etcd.recipes.discovery

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.fetchAndIncrement

/**
 * Picks one instance from the currently-available set (a [ServiceProvider]'s live
 * instances minus any ejected by `noteError`), or null if the set is empty.
 * Community-extensible: implement this to add a custom load-balancing policy.
 */
fun interface ProviderStrategy {
  fun select(instances: List<ServiceInstance>): ServiceInstance?
}

/**
 * Uniformly random selection — the default. Stateless, so a single instance is safe to
 * share across providers.
 */
object RandomStrategy : ProviderStrategy {
  override fun select(instances: List<ServiceInstance>): ServiceInstance? = instances.randomOrNull()
}

/**
 * Even round-robin via a monotonic cursor. `Math.floorMod` keeps the index non-negative
 * across `AtomicInt` overflow and adapts automatically to list-size changes.
 *
 * STATEFUL — use a fresh instance per [ServiceProvider]; sharing one across providers
 * interleaves their cursors.
 */
class RoundRobinStrategy : ProviderStrategy {
  private val cursor = AtomicInt(0)

  override fun select(instances: List<ServiceInstance>): ServiceInstance? {
    if (instances.isEmpty()) return null
    return instances[Math.floorMod(cursor.fetchAndIncrement(), instances.size)]
  }
}

/**
 * Session affinity: returns the previously-selected instance while it is still present
 * (by value-equality, id-independent), otherwise [delegate]s to pick a new one and
 * remembers it.
 *
 * STATEFUL — use a fresh instance per [ServiceProvider].
 */
class StickyStrategy(
  private val delegate: ProviderStrategy = RandomStrategy,
) : ProviderStrategy {
  private val last = AtomicReference<ServiceInstance?>(null)

  @Suppress("ReturnCount")
  override fun select(instances: List<ServiceInstance>): ServiceInstance? {
    if (instances.isEmpty()) {
      last.store(null)
      return null
    }
    val current = last.load()
    if (current != null && current in instances) return current
    val picked = delegate.select(instances)
    last.store(picked)
    return picked
  }
}
