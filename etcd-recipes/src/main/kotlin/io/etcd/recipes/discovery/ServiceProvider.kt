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
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getChildrenValues
import java.io.Closeable

class ServiceProvider(
  private val client: Client,
  namesPath: String,
  val serviceName: String,
) : Closeable {
  // Direct path to the service's instances. The previous implementation lazily
  // constructed a nested ServiceDiscovery rooted at namesPath, which caused
  // path double-nesting (`namesPath/names/...`) and escaped lifecycle tracking
  // by the parent ServiceDiscovery.
  private val instancesPath: String = namesPath.appendToPath(serviceName)

  // "No instances available" is an ordinary discovery condition, not a programming
  // error, so surface the library's typed exception (matching ServiceDiscovery's
  // absent-instance contract) instead of letting List.random() throw a bare,
  // context-free NoSuchElementException.
  @Throws(EtcdRecipeException::class)
  fun getInstance(): ServiceInstance =
    getAllInstances().randomOrNull() ?: throw EtcdRecipeException("No instances available for service $serviceName")

  fun getAllInstances(): List<ServiceInstance> =
    client.getChildrenValues(instancesPath).map { it.asString }.map { ServiceInstance.toObject(it) }

  override fun close() {
    // No owned resources: provider does not hold a watcher, lease, or executor.
  }
}
