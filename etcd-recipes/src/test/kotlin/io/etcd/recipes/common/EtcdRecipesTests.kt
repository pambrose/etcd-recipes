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

package io.etcd.recipes.common

import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedReadWriteLock
import io.etcd.recipes.lock.DistributedSemaphore
import io.etcd.recipes.queue.DistributedQueue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf

/** The [EtcdRecipes] factory news up each recipe bound to the shared client. */
class EtcdRecipesTests : StringSpec() {
  private val base = "/common/${javaClass.simpleName}"

  init {
    "factory creates recipes bound to the shared client" {
      connectToEtcd(urls) { client ->
        val recipes = EtcdRecipes(client)
        recipes.mutex("$base/m").shouldBeInstanceOf<DistributedMutex>()
        recipes.readWriteLock("$base/rw").shouldBeInstanceOf<DistributedReadWriteLock>()
        recipes.semaphore("$base/s", 2).shouldBeInstanceOf<DistributedSemaphore>()
        recipes.distributedQueue("$base/q").shouldBeInstanceOf<DistributedQueue>()
        recipes.leaderLatch("$base/e").shouldBeInstanceOf<LeaderLatch>()
        recipes.pathChildrenCache("$base/c").shouldBeInstanceOf<PathChildrenCache>()
        recipes.nodeCache("$base/n", StringCodec).shouldBeInstanceOf<NodeCache<*>>()
        recipes.serviceDiscovery("$base/d").shouldBeInstanceOf<ServiceDiscovery>()
        recipes.distributedAtomicLong("$base/a").shouldBeInstanceOf<DistributedAtomicLong>()
      }
    }
  }
}
