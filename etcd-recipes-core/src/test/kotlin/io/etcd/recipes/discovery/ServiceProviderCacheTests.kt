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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * The started provider is backed by a watch-updated ServiceCache: reads are in-memory
 * and converge on registrations, selection load-balances, and noteError ejects an
 * instance until its down window elapses.
 */
class ServiceProviderCacheTests : StringSpec() {
  private val path = "/discovery/${javaClass.simpleName}"
  private val name = "cached-svc"

  init {
    beforeSpec { urls }

    "a started provider serves cache-backed instances and round-robins across all" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        withServiceDiscovery(client, path) {
          val instances = (1..4).map { ServiceInstance(name, "payload-$it") }
          instances.forEach { registerService(it) }

          withServiceProvider(name, RoundRobinStrategy()) {
            start()
            pollUntil(20.seconds) { getAllInstances().size == instances.size } shouldBe true

            val hits = (1..instances.size * 3).map { getInstance().jsonPayload }.toSet()
            hits shouldContainExactlyInAnyOrder instances.map { it.jsonPayload }
          }
        }
      }
    }

    "noteError removes an instance from selection until the down window elapses" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        withServiceDiscovery(client, path) {
          val instances = (1..3).map { ServiceInstance(name, "note-$it") }
          instances.forEach { registerService(it) }

          withServiceProvider(name, RoundRobinStrategy(), errorThreshold = 1, downPeriod = 2.seconds) {
            start()
            pollUntil(20.seconds) { getAllInstances().size == instances.size } shouldBe true

            val target = getInstance()
            noteError(target)

            // Ejected across many selections while inside the window.
            repeat(12) { (getInstance().jsonPayload == target.jsonPayload) shouldBe false }

            // Eligible again once the window elapses.
            pollUntil(10.seconds) {
              (1..6).map { getInstance().jsonPayload }.contains(target.jsonPayload)
            } shouldBe true
          }
        }
      }
    }

    "close is clean and idempotent after start" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        withServiceDiscovery(client, path) {
          registerService(ServiceInstance(name, "solo"))
          val provider = serviceProvider(name).start()
          pollUntil(20.seconds) { provider.getAllInstances().size == 1 } shouldBe true
          provider.close()
          provider.close() // idempotent no-op
          provider.hasExceptions shouldBe false
        }
      }
    }
  }
}
