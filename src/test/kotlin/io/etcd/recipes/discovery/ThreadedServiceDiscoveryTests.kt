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

import com.google.common.collect.Maps
import com.sudothought.common.util.sleep
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.blockingThreads
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentMap
import kotlin.time.seconds

class ThreadedServiceDiscoveryTests {
    val urls = listOf("http://localhost:2379")
    val path = "/discovery/${javaClass.simpleName}"
    val threadCount = 5
    val serviceCount = 10
    val contextMap: ConcurrentMap<Int, ServiceDiscoveryContext> = Maps.newConcurrentMap()

    class ServiceDiscoveryContext(val serviceDiscovery: ServiceDiscovery) {
        val serviceMap: ConcurrentMap<String, ServiceInstance> = Maps.newConcurrentMap()
    }

    @Test
    fun discoveryTest() {
        // Create services
        blockingThreads(threadCount) { i ->
            // Close ServiceDiscovery objects at end
            val sd = ServiceDiscovery(urls, path)
            val context = ServiceDiscoveryContext(sd)
            contextMap[i] = context

            repeat(serviceCount) { j ->
                val service = ServiceInstance("TestInstance$j", TestPayload(j).toJson())
                context.serviceMap[service.id] = service
                println("Registering: $service")
                sd.registerService(service)
            }
        }

        // Query services
        blockingThreads(threadCount) {
            ServiceDiscovery(urls, path)
                .use { sd ->
                    contextMap.values.forEach { context ->
                        context.serviceMap.forEach { (_, service) ->
                            println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
                            sd.queryForInstance(service.name, service.id) shouldEqual service
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
                println("Updating service: $service")
                context.serviceDiscovery.updateService(service)
            }
        }

        // Query updated services
        blockingThreads(threadCount) {
            ServiceDiscovery(urls, path)
                .use { sd ->
                    contextMap.values.forEach { context ->
                        context.serviceMap.forEach { (_, service) ->
                            println("Retrieved updated value: ${sd.queryForInstance(service.name, service.id)}")
                            sd.queryForInstance(service.name, service.id) shouldEqual service
                        }

                    }
                }
        }

        ServiceDiscovery(urls, path)
            .use { sd ->
                println("Retrieved all names: ${sd.queryForNames().size}")
                sd.queryForNames().size shouldEqual threadCount * serviceCount
            }

        // Delete services
        contextMap.forEach { (_, context) ->
            context.serviceMap.forEach { (_, service) ->
                val payload = TestPayload.toObject(service.jsonPayload)
                payload.testval = payload.testval * -1
                service.jsonPayload = payload.toJson()
                println("Unregistering service: $service")
                context.serviceDiscovery.unregisterService(service)
            }
        }

        // Give unregister a chance to take place
        sleep(5.seconds)

        // Query deleted services
        blockingThreads(threadCount) {
            ServiceDiscovery(urls, path)
                .use { sd ->
                    contextMap.values.forEach { context ->
                        context.serviceMap.forEach { (_, service) ->
                            println("Query deleted service: ${service.name}  ${service.id}")
                            invoking {
                                sd.queryForInstance(service.name, service.id)
                            } shouldThrow EtcdRecipeException::class
                        }
                    }
                }
        }

        // Close ServiceDiscovery objects
        contextMap.values.forEach { it.serviceDiscovery.close() }
    }

    companion object : KLogging()
}