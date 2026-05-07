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

import com.pambrose.common.util.sleep
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.recipes.common.ExceptionHolder
import io.etcd.recipes.common.captureException
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.nonblockingThreads
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class ServiceCacheTests : StringSpec() {
    init {
        "serviceCacheTest" {
            val path = "/discovery/ServiceCacheTests"
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
                        sleep(2.seconds)

                        instances.size shouldBe 0
                        serviceName shouldBe name
                        urls shouldBe urls

                        addListenerForChanges(object : ServiceCacheListener {
                            override fun cacheChanged(
                                eventType: EventType,
                                isAdd: Boolean,
                                serviceName: String,
                                serviceInstance: ServiceInstance?,
                            ) {
                                captureException(holder) {
                                    serviceName.split("/").first() shouldBe name

                                    if (eventType == EventType.PUT) {
                                        if (isAdd) registerCounter.incrementAndGet() else updateCounter.incrementAndGet()

                                        serviceInstance?.name shouldBe name
                                    }

                                    if (eventType == EventType.DELETE) {
                                        unregisterCounter.incrementAndGet()
                                        serviceInstance?.name shouldBe name
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

                                        sleep(1.seconds)

                                        val payload = TestPayload.toObject(service.jsonPayload)
                                        payload.testval = payload.testval * -1
                                        service.jsonPayload = payload.toJson()
                                        logger.debug { "Updating: ${service.name} ${service.id}" }
                                        updateService(service)

                                        sleep(1.seconds)

                                        logger.debug { "Unregistering: ${service.name} ${service.id}" }
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
            registerCounter.get() shouldBe threadCount * serviceCount
            updateCounter.get() shouldBe threadCount * serviceCount
            unregisterCounter.get() shouldBe threadCount * serviceCount
            totalCounter.get() shouldBe (threadCount * serviceCount) * 3
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
