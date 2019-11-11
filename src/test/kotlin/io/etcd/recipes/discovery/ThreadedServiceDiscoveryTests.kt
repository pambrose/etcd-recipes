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

import com.google.common.collect.Maps.newConcurrentMap
import com.sudothought.common.util.sleep
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentMap
import kotlin.time.seconds

class ThreadedServiceDiscoveryTests {
    val path = "/discovery/${javaClass.simpleName}"
    val threadCount = 5
    val serviceCount = 10
    val contextMap: ConcurrentMap<Int, ServiceDiscoveryContext> = newConcurrentMap()

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
                    logger.info { "Registering: $service" }
                    sd.registerService(service)
                }
            }

            // Query services
            blockingThreads(threadCount) {
                ServiceDiscovery(client, path).use { sd ->
                    contextMap.values.forEach { context ->
                        context.serviceMap.forEach { (_, service) ->
                            logger.info { "Retrieved value: ${sd.queryForInstance(service.name, service.id)}" }
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
                    logger.info { "Updating service: $service" }
                    context.serviceDiscovery.updateService(service)
                }
            }

            // Query updated services
            blockingThreads(threadCount) {
                ServiceDiscovery(client, path).use { sd ->
                    contextMap.values.forEach { context ->
                        context.serviceMap.forEach { (_, service) ->
                            logger.info { "Retrieved updated value: ${sd.queryForInstance(service.name, service.id)}" }
                            sd.queryForInstance(service.name, service.id) shouldEqual service
                        }
                    }
                }
            }

            ServiceDiscovery(client, path).use { sd ->
                logger.info { "Retrieved all names: ${sd.queryForNames().size}" }
                sd.queryForNames().size shouldEqual threadCount * serviceCount
            }

            // Delete services
            contextMap.forEach { (_, context) ->
                context.serviceMap.forEach { (_, service) ->
                    val payload = TestPayload.toObject(service.jsonPayload)
                    payload.testval = payload.testval * -1
                    service.jsonPayload = payload.toJson()
                    logger.info { "Unregistering service: $service" }
                    context.serviceDiscovery.unregisterService(service)
                }
            }
        }

        // Give unregister a chance to take place
        sleep(5.seconds)

        // Query deleted services
        blockingThreads(threadCount) {
            connectToEtcd(urls) { client ->
                ServiceDiscovery(client, path).use { sd ->
                    contextMap.values.forEach { context ->
                        context.serviceMap.forEach { (_, service) ->
                            val name = service.name
                            logger.info { "Query deleted service: $name  ${service.id}" }
                            invoking { sd.queryForInstance(name, service.id) } shouldThrow EtcdRecipeException::class
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