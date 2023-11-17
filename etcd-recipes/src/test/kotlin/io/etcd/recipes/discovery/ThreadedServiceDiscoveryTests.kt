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
import com.google.common.collect.Maps.newConcurrentMap
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import mu.two.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration.Companion.seconds

class ThreadedServiceDiscoveryTests {
  val path = "/discovery/${javaClass.simpleName}"
  private val threadCount = 5
  private val serviceCount = 10
  private val contextMap: ConcurrentMap<Int, ServiceDiscoveryContext> = newConcurrentMap()

  class ServiceDiscoveryContext(val serviceDiscovery: ServiceDiscovery) {
    val serviceMap: ConcurrentMap<String, ServiceInstance> = newConcurrentMap()
  }

  @Test
  fun discoveryTest() {
    connectToEtcd(urls) { client ->

      // Create services
      blockingThreads(threadCount) { i ->
        // Close ServiceDiscovery objects at end
        val sd = ServiceDiscovery(client, path)
        val context = ServiceDiscoveryContext(sd)
        contextMap[i] = context

        repeat(serviceCount) { j ->
          val service = ServiceInstance("TestInstance$j", TestPayload(j).toJson())
          context.serviceMap[service.id] = service
          logger.debug { "Registering: $service" }
          sd.registerService(service)
        }
      }

      // Query services
      blockingThreads(threadCount) {
        withServiceDiscovery(client, path) {
          contextMap.values.forEach { context ->
            context.serviceMap.forEach { (_, service) ->
              val inst = queryForInstance(service.name, service.id)
              logger.debug { "Retrieved value: $inst" }
              inst shouldBeEqualTo service
            }
          }
        }
      }

      // Update services
      contextMap.forEach { (_, context) ->
        context.serviceMap.forEach { (_, service) ->
          val payload = TestPayload.toObject(service.jsonPayload)
          payload.testval = payload.testval * -1
          service.jsonPayload = payload.toJson()
          logger.debug { "Updating service: $service" }
          context.serviceDiscovery.updateService(service)
        }
      }

      // Query updated services
      blockingThreads(threadCount) {
        withServiceDiscovery(client, path) {
          contextMap.values.forEach { context ->
            context.serviceMap.forEach { (_, service) ->
              val inst = queryForInstance(service.name, service.id)
              logger.debug { "Retrieved updated value: $inst" }
              inst shouldBeEqualTo service
            }
          }
        }
      }

      withServiceDiscovery(client, path) {
        val size = queryForNames().size
        logger.debug { "Retrieved all names: $size" }
        size shouldBeEqualTo threadCount * serviceCount
      }

      // Delete services
      contextMap.forEach { (_, context) ->
        context.serviceMap.forEach { (_, service) ->
          val payload = TestPayload.toObject(service.jsonPayload)
          payload.testval = payload.testval * -1
          service.jsonPayload = payload.toJson()
          logger.debug { "Unregistering service: $service" }
          context.serviceDiscovery.unregisterService(service)
        }
      }
    }

    // Give unregister a chance to take place
    sleep(5.seconds)

    // Query deleted services
    blockingThreads(threadCount) {
      connectToEtcd(urls) { client ->
        withServiceDiscovery(client, path) {
          contextMap.values.forEach { context ->
            context.serviceMap.forEach { (_, service) ->
              val name = service.name
              logger.debug { "Query deleted service: $name  ${service.id}" }
              invoking { queryForInstance(name, service.id) } shouldThrow EtcdRecipeException::class
            }
          }
        }
      }
    }

    // Close ServiceDiscovery objects
    contextMap.values.forEach { it.serviceDiscovery.close() }
  }

  companion object : KLogging()
}
