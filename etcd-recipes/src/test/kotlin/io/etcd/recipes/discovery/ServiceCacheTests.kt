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
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.recipes.common.*
import mu.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

class ServiceCacheTests {

  @Test
  fun serviceCacheTest() {
    val path = "/discovery/${javaClass.simpleName}"
    val threadCount = 10
    val serviceCount = 10
    val name = "ServiceCacheTest"
    val holder = ExceptionHolder()
    val registerCounter = AtomicInteger(0)
    val updateCounter = AtomicInteger(0)
    val unregisterCounter = AtomicInteger(0)
    val totalCounter = AtomicInteger(0)

    connectToEtcd(urls) { client ->
      withServiceDiscovery(client, path) {
        withServiceCache(name) {
          start()
          sleep(Duration.seconds(2))

          instances.size shouldBeEqualTo 0
          serviceName shouldBeEqualTo name
          urls shouldBeEqualTo urls

          addListenerForChanges(object : ServiceCacheListener {
            override fun cacheChanged(
              eventType: EventType,
              isAdd: Boolean,
              serviceName: String,
              serviceInstance: ServiceInstance?
            ) {
              captureException(holder) {
                //println("Comparing $serviceName and $name")
                serviceName.split("/").first() shouldBeEqualTo name

                if (eventType == EventType.PUT) {
                  if (isAdd) registerCounter.incrementAndGet() else updateCounter.incrementAndGet()

                  serviceInstance?.name shouldBeEqualTo name
                }

                if (eventType == EventType.DELETE) {
                  unregisterCounter.incrementAndGet()
                  serviceInstance?.name shouldBeEqualTo name
                }
              }
            }
          })

          addListenerForChanges { _, _, _, _ -> totalCounter.incrementAndGet() }

          val (finishedLatch, holder2) =
            nonblockingThreads(threadCount) {
              withServiceDiscovery(client, path) {
                repeat(serviceCount) {
                  val service = ServiceInstance(name, TestPayload(it).toJson())
                  logger.debug { "Registering: ${service.name} ${service.id}" }
                  registerService(service)

                  sleep(Duration.seconds(1))

                  val payload = TestPayload.toObject(service.jsonPayload)
                  payload.testval = payload.testval * -1
                  service.jsonPayload = payload.toJson()
                  logger.debug { "Updating: ${service.name} ${service.id}" }
                  updateService(service)

                  sleep(Duration.seconds(1))

                  logger.debug { "Unregistering: ${service.name} ${service.id}" }
                  unregisterService(service)

                  sleep(Duration.seconds(1))
                }
              }
            }
          finishedLatch.await()
          holder2.checkForException()
        }
      }
    }

    // Wait for deletes to propagate
    sleep(Duration.seconds(5))

    holder.checkForException()
    registerCounter.get() shouldBeEqualTo threadCount * serviceCount
    updateCounter.get() shouldBeEqualTo threadCount * serviceCount
    unregisterCounter.get() shouldBeEqualTo threadCount * serviceCount
    totalCounter.get() shouldBeEqualTo (threadCount * serviceCount) * 3
  }

  companion object : KLogging()
}