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

package io.etcd.recipes.coroutines

import io.etcd.recipes.discovery.ServiceCache
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance

/** Suspending twin of [ServiceDiscovery.registerService]. */
suspend fun ServiceDiscovery.awaitRegisterService(service: ServiceInstance): Unit =
  etcdInterruptible { registerService(service) }

/** Suspending twin of [ServiceDiscovery.updateService]. */
suspend fun ServiceDiscovery.awaitUpdateService(service: ServiceInstance): Unit =
  etcdInterruptible { updateService(service) }

/** Suspending twin of [ServiceDiscovery.unregisterService]. */
suspend fun ServiceDiscovery.awaitUnregisterService(service: ServiceInstance): Unit =
  etcdInterruptible { unregisterService(service) }

/** Suspending twin of [ServiceDiscovery.queryForNames]. */
suspend fun ServiceDiscovery.awaitQueryForNames(): List<String> = etcdInterruptible { queryForNames() }

/** Suspending twin of [ServiceDiscovery.queryForInstances]. */
suspend fun ServiceDiscovery.awaitQueryForInstances(name: String): List<ServiceInstance> =
  etcdInterruptible { queryForInstances(name) }

/** Suspending twin of [ServiceDiscovery.queryForInstance]. */
suspend fun ServiceDiscovery.awaitQueryForInstance(
  name: String,
  id: String,
): ServiceInstance = etcdInterruptible { queryForInstance(name, id) }

/** Suspending twin of [ServiceCache.start]. */
suspend fun ServiceCache.awaitStart(): ServiceCache = etcdInterruptible { start() }
