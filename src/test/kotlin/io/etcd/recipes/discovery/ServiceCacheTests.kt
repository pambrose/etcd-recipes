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
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.nonblockingThreads
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.seconds

class ServiceCacheTests {

    @Test
    fun serviceCacheTest() {
        val path = "/discovery/${javaClass.simpleName}"
        val threadCount = 10
        val serviceCount = 10
        val name = "ServiceCacheTest"
        val registerCounter = AtomicInteger(0)
        val updateCounter = AtomicInteger(0)
        val unregisterCounter = AtomicInteger(0)
        val holder = ExceptionHolder()
        val totalCounter = AtomicInteger(0)

        connectToEtcd(urls) { client ->
            withServiceDiscovery(client, path) {
                withServiceCache(name) {
                    start()
                    sleep(2.seconds)

                    instances.size shouldEqualTo 0
                    serviceName shouldEqual name
                    urls shouldEqual urls

                    addListenerForChanges(object : ServiceCacheListener {
                        override fun cacheChanged(eventType: EventType,
                                                  isAdd: Boolean,
                                                  serviceName: String,
                                                  serviceInstance: ServiceInstance?) {
                            captureException(holder) {
                                //println("Comparing $serviceName and $name")
                                serviceName.split("/").first() shouldEqual name

                                if (eventType == EventType.PUT) {
                                    if (isAdd) registerCounter.incrementAndGet() else updateCounter.incrementAndGet()

                                    serviceInstance?.name shouldEqual name
                                }

                                if (eventType == EventType.DELETE) {
                                    unregisterCounter.incrementAndGet()
                                    serviceInstance?.name shouldEqual name
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
                                    //logger.info { "Registering: ${service.name} ${service.id}" }
                                    registerService(service)

                                    sleep(1.seconds)

                                    val payload = TestPayload.toObject(service.jsonPayload)
                                    payload.testval = payload.testval * -1
                                    service.jsonPayload = payload.toJson()
                                    //logger.info { "Updating: ${service.name} ${service.id}" }
                                    updateService(service)

                                    sleep(1.seconds)

                                    //logger.info { "Unregistering: ${service.name} ${service.id}" }
                                    unregisterService(service)

                                    sleep(1.seconds)
                                }
                            }
                        }
                    finishedLatch.await()
                    holder2.checkForException()
                }
            }
        }

        // Wait for deletes to propagate
        sleep(5.seconds)

        holder.checkForException()
        registerCounter.get() shouldEqual threadCount * serviceCount
        updateCounter.get() shouldEqual threadCount * serviceCount
        unregisterCounter.get() shouldEqual threadCount * serviceCount
        totalCounter.get() shouldEqual (threadCount * serviceCount) * 3
    }

    companion object : KLogging()
}