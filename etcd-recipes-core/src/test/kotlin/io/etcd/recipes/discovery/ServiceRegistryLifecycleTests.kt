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
import io.etcd.jetcd.options.LeaseOption
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * A registration's lease must be released whenever the registration ends. Two paths
 * used to leave a keep-alive renewing a lease forever:
 *
 * - re-registering an instance whose key had vanished replaced its context without
 *   closing the old one's healer;
 * - `close()` stopped at the first instance whose cleanup delete failed, so every later
 *   instance kept its keep-alive and healer running inside a "closed" registry.
 */
class ServiceRegistryLifecycleTests : StringSpec() {
  private val base = "/discovery/${javaClass.simpleName}"

  private fun Client.leaseOf(key: String): Long = getResponse(key).kvs.single().lease

  private fun Client.leaseGone(leaseId: Long): Boolean =
    leaseClient.timeToLive(leaseId, LeaseOption.DEFAULT).get(5, TimeUnit.SECONDS).ttl == -1L

  init {
    "re-registering after the key vanished releases the previous registration's lease" {
      connectToEtcd(urls) { client ->
        val path = "$base/reregister"
        client.deleteChildren(path)

        ServiceRegistry(client, path).use { registry ->
          val instance = ServiceInstance("svc", "{}")
          registry.registerService(instance)
          val key = "$path/names/svc/${instance.id}"
          val firstLease = client.leaseOf(key)

          // The key vanishes while its lease lives on (deleted out of band, or a heal gave up)
          client.deleteKey(key)
          registry.registerService(instance)

          withClue("the earlier registration's keep-alive is still renewing lease $firstLease") {
            pollUntil(10.seconds) { client.leaseGone(firstLease) } shouldBe true
          }
        }
        client.deleteChildren(path)
      }
    }

    "close releases every registration even when one instance's cleanup delete fails" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/close"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)

        val registry = ServiceRegistry(client, path)
        val instances = listOf(ServiceInstance("svc", "a"), ServiceInstance("svc", "b"))
        instances.forEach { registry.registerService(it) }
        val leases = instances.map { etcd.leaseOf("$path/names/svc/${it.id}") }

        client.beforeDelete.store { throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("leader changed")) }
        shouldNotThrowAny { registry.close() }

        for (lease in leases) {
          withClue("lease $lease is still being renewed after close()") {
            pollUntil(10.seconds) { etcd.leaseGone(lease) } shouldBe true
          }
        }
        etcd.deleteChildren(path)
      }
    }
  }
}
