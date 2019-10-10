/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.discovery

import com.sudothought.common.util.sleep
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.recipes.common.ExceptionHolder
import io.etcd.recipes.common.captureException
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.nonblockingThreads
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.seconds

class ServiceCacheTests {

    @Test
    fun serviceCacheTest() {
        val urls = listOf("http://localhost:2379")
        val path = "/discovery/${javaClass.simpleName}"
        val threadCount = 10
        val serviceCount = 10
        val name = "ServiceCacheTest"
        val registerCounter = AtomicInteger(0)
        val updateCounter = AtomicInteger(0)
        val unregisterCounter = AtomicInteger(0)
        val holder = ExceptionHolder()

        ServiceDiscovery(urls, path).use { cachesd ->

            cachesd.serviceCache(name).apply {

                start()
                sleep(2.seconds)

                instances.size shouldEqualTo 0
                serviceName shouldEqual name
                urls shouldEqual urls

                addListenerForChanges(object : ServiceCacheListener {
                    override fun cacheChanged(eventType: EventType,
                                              isNew: Boolean,
                                              serviceName: String,
                                              serviceInstance: ServiceInstance?) {
                        captureException(holder) {
                            serviceName.split("/").dropLast(1).last() shouldEqual name

                            if (eventType == EventType.PUT) {
                                if (isNew) registerCounter.incrementAndGet() else updateCounter.incrementAndGet()
                                serviceInstance!!.name shouldEqual name
                            }

                            if (eventType == EventType.DELETE) {
                                unregisterCounter.incrementAndGet()
                                serviceInstance!!.name shouldEqual name
                            }
                        }
                    }
                })
            }

            val (latch0, exception0) =
                nonblockingThreads(threadCount) {
                    ServiceDiscovery(urls, path).use { sd ->
                        repeat(serviceCount) {
                            val service = ServiceInstance(name, TestPayload(it).toJson())
                            println("Registering: ${service.name} ${service.id}")
                            sd.registerService(service)

                            sleep(1.seconds)

                            val payload = TestPayload.toObject(service.jsonPayload)
                            payload.testval = payload.testval * -1
                            service.jsonPayload = payload.toJson()
                            println("Updating: ${service.name} ${service.id}")
                            sd.updateService(service)

                            sleep(1.seconds)

                            println("Unregistering: ${service.name} ${service.id}")
                            sd.unregisterService(service)

                            sleep(1.seconds)
                        }
                    }
                }

            latch0.await()
            exception0.checkForException()
        }

        // Wait for deletes to propagate
        sleep(5.seconds)

        holder.checkForException()
        registerCounter.get() shouldEqual threadCount * serviceCount
        updateCounter.get() shouldEqual threadCount * serviceCount
        unregisterCounter.get() shouldEqual threadCount * serviceCount
    }
}