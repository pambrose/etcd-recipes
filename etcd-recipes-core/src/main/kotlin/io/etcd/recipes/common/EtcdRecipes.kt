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

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedReadWriteLock
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.DistributedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A thin factory that news up recipes bound to a shared [client] (and [resilience]), so an
 * application wires the connection once and asks the factory for path-scoped recipes. Used by the
 * Spring Boot starter and Ktor plugin, but usable standalone. Each method just constructs the
 * corresponding recipe; the caller owns its `start()`/`close()` lifecycle.
 */
class EtcdRecipes
  @JvmOverloads
  constructor(
    private val client: Client,
    private val resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) {
    fun mutex(lockPath: String): DistributedMutex = DistributedMutex(client, lockPath, resilience = resilience)

    fun readWriteLock(lockPath: String): DistributedReadWriteLock =
      DistributedReadWriteLock(client, lockPath, resilience = resilience)

    fun semaphore(
      semaphorePath: String,
      permits: Int,
    ): DistributedSemaphore = DistributedSemaphore(client, semaphorePath, permits, resilience = resilience)

    fun distributedQueue(queuePath: String): DistributedQueue = DistributedQueue(client, queuePath, resilience)

    fun distributedPriorityQueue(
      queuePath: String,
      minimumWaitTime: Duration = 0.milliseconds,
    ): DistributedPriorityQueue = DistributedPriorityQueue(client, queuePath, minimumWaitTime, resilience)

    fun leaderLatch(electionPath: String): LeaderLatch = LeaderLatch(client, electionPath, resilience = resilience)

    fun pathChildrenCache(cachePath: String): PathChildrenCache =
      PathChildrenCache(client, cachePath, resilience = resilience)

    fun <T> nodeCache(
      key: String,
      codec: EtcdCodec<T>,
    ): NodeCache<T> = NodeCache(client, key, codec, resilience)

    fun serviceDiscovery(servicePath: String): ServiceDiscovery =
      ServiceDiscovery(client, servicePath, resilienceConfig = resilience)

    fun distributedAtomicLong(counterPath: String): DistributedAtomicLong =
      DistributedAtomicLong(client, counterPath, resilience = resilience)
  }
