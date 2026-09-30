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

package io.etcd.recipes.examples.discovery

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.discovery.withServiceDiscovery
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("TooGenericExceptionCaught")
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val servicePath = "/services/test"

  connectToEtcd(urls) { client ->
    withServiceDiscovery(client, servicePath) {
      val payload = IntPayload(-999)
      val service = ServiceInstance("TestName", payload.toJson())

      logger.info {service.toJson()}

      logger.info {"Registering"}
      registerService(service)
      logger.info {"Retrieved value: ${queryForInstance(service.name, service.id)}"}
      logger.info {"Retrieved values: ${queryForInstances(service.name)}"}
      logger.info {"Retrieved names: ${queryForNames()}"}

      Thread.sleep(2_000)
      logger.info {"Updating"}
      payload.intval = -888
      service.jsonPayload = payload.toJson()
      updateService(service)
      logger.info {"Retrieved value: ${queryForInstance(service.name, service.id)}"}
      logger.info {"Retrieved values: ${queryForInstances(service.name)}"}
      logger.info {"Retrieved names: ${queryForNames()}"}

      Thread.sleep(2_000)
      logger.info {"Unregistering"}
      unregisterService(service)
      Thread.sleep(3_000)

      try {
        logger.info {"Retrieved value: ${queryForInstance(service.name, service.id)}"}
      } catch (e: Throwable) {
        println("Exception: $e")
      }

      Thread.sleep(2_000)
    }
  }
}
