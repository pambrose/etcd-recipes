/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldEndWith
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SerialServiceDiscoveryTests {
  val path = "/discovery/${javaClass.simpleName}"

  @Test
  fun badArgsTest() {
    connectToEtcd(urls) { client ->
      invoking { ServiceDiscovery(client, "") } shouldThrow IllegalArgumentException::class
    }
  }

  @Test
  fun discoveryTest() {
    connectToEtcd(urls) { client ->
      withServiceDiscovery(client, path) {

        val payload = TestPayload(-999)
        val service = ServiceInstance("TestName", payload.toJson())

        logger.debug { service.toJson() }

        logger.debug { "Registering" }
        registerService(service)

        logger.debug { "Retrieved value: ${queryForInstance(service.name, service.id)}" }
        queryForInstance(service.name, service.id) shouldBeEqualTo service

        logger.debug { "Retrieved values: ${queryForInstances(service.name)}" }
        queryForInstances(service.name) shouldBeEqualTo listOf(service)

        logger.debug { "Retrieved names: ${queryForNames()}" }
        queryForNames().first() shouldEndWith service.id

        logger.debug { "Updating payload" }
        payload.testval = -888
        service.jsonPayload = payload.toJson()
        updateService(service)

        logger.debug { "Retrieved value: ${queryForInstance(service.name, service.id)}" }
        queryForInstance(service.name, service.id) shouldBeEqualTo service

        logger.debug { "Retrieved values: ${queryForInstances(service.name)}" }
        queryForInstances(service.name) shouldBeEqualTo listOf(service)

        logger.debug { "Retrieved names: ${queryForNames()}" }
        queryForNames().first() shouldEndWith service.id

        logger.debug { "Unregistering" }
        unregisterService(service)
        sleep(3.seconds)

        queryForNames().size shouldBeEqualTo 0
        queryForInstances(service.name).size shouldBeEqualTo 0

        invoking { queryForInstance(service.name, service.id) } shouldThrow EtcdRecipeException::class

        try {
          logger.debug { "Retrieved value: ${queryForInstance(service.name, service.id)}" }
        } catch (e: EtcdRecipeException) {
          logger.debug { "Exception: $e" }
        }
      }
    }
  }

  companion object : KLogging()
}