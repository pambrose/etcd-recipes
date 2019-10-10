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

package io.etcd.recipes.discovery

import com.sudothought.common.util.sleep
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.captureException
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.nonblockingThreads
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.seconds

class ServiceCacheTests {

    @Test
    fun serviceCacheTest() {
        val urls = listOf("http://localhost:2379")
        val path = "/discovery/${javaClass.simpleName}"
        val threadCount = 10
        val serviceCount = 10
        val waitLatch = CountDownLatch(1)
        val name = "ServiceCacheTest"

        ServiceDiscovery(urls, path).use { cachesd ->

            val changeCounter = AtomicInteger(0)
            val watchException = AtomicReference<Throwable>()

            cachesd.serviceCache(name).apply {

                start()
                sleep(2.seconds)

                instances.size shouldEqualTo 0
                serviceName shouldEqual name
                urls shouldEqual urls

                addListenerForChanges(object : ServiceCacheListener {
                    override fun cacheChanged(eventType: WatchEvent.EventType,
                                              serviceName: String,
                                              serviceInstance: ServiceInstance?) {
                        captureException(watchException) {
                            if (eventType == WatchEvent.EventType.PUT) {
                                changeCounter.incrementAndGet()

                                serviceName.split("/").dropLast(1).last() shouldEqual name
                                serviceInstance!!.name shouldEqual name
                            }
                        }
                    }
                })
            }

            val (latch, exception) =
                nonblockingThreads(threadCount, waitLatch) {
                    ServiceDiscovery(urls, path).use { sd ->
                        repeat(serviceCount) {
                            val service = ServiceInstance(name, TestPayload(it).toJson())
                            println("Registering: ${service.name} ${service.id}")
                            sd.registerService(service)

                            sleep(1.seconds)
                        }
                    }
                }

            waitLatch.countDown()
            latch.await()

            watchException.checkForException()
            changeCounter.get() shouldEqual threadCount * serviceCount

            exception.checkForException()

        }

    }

}