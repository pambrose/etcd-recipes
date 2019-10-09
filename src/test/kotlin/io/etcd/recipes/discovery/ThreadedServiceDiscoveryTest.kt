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
import io.etcd.recipes.common.blockingThreads
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test

class ThreadedServiceDiscoveryTest {
    val urls = listOf("http://localhost:2379")
    val path = "/discovery/${javaClass.simpleName}"
    val threadCount = 2
    val serviceCount = 2
    val contextMap = Maps.newConcurrentMap<Int, ServiceDiscoveryContext>()

    class ServiceDiscoveryContext(val serviceDiscovery: ServiceDiscovery) {
        val serviceMap = Maps.newConcurrentMap<String, ServiceInstance>()
    }

    @Test
    fun discoveryTest() {

        blockingThreads(threadCount) {
            // Close ServiceDiscovery objects at end
            val sd = ServiceDiscovery(urls, path)
            val context = ServiceDiscoveryContext(sd)
            contextMap[it] = context

            repeat(serviceCount) {
                val service = ServiceInstance("TestInstance$it", TestPayload(it).toJson())
                context.serviceMap[service.id] = service
                println("Registering: $service")
                sd.registerService(service)
            }
        }

        // Query the services
        blockingThreads(threadCount) {
            ServiceDiscovery(urls, path).use { sd ->
                contextMap.values.forEach { context ->
                    context.serviceMap.forEach { k, service ->
                        println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
                        sd.queryForInstance(service.name, service.id) shouldEqual service
                    }

                }
            }
        }

        // Update the services
        blockingThreads(threadCount) {
            contextMap.forEach { k, context ->
                context.serviceMap.forEach { id, service ->
                    val payload = TestPayload.toObject(service.jsonPayload)
                    payload.testval = payload.testval * -1
                    service.jsonPayload = payload.toJson()
                    println("Updating service: $service")
                    context.serviceDiscovery.updateService(service)
                }
            }
        }

        // Query the updated services
        blockingThreads(threadCount) {
            ServiceDiscovery(urls, path).use { sd ->
                contextMap.values.forEach { context ->
                    context.serviceMap.forEach { k, service ->
                        println("Retrieved updated value: ${sd.queryForInstance(service.name, service.id)}")
                        sd.queryForInstance(service.name, service.id) shouldEqual service
                    }

                }
            }
        }

        ServiceDiscovery(urls, path).use { sd ->
            println("Retrieved all names: ${sd.queryForNames().size}")
            sd.queryForNames().size shouldEqual threadCount * serviceCount
        }

        contextMap.values.forEach { it.serviceDiscovery.close() }

        /*
        ServiceDiscovery(urls, path).use { sd ->

            val payload = TestPayload(-999)
            val service = ServiceInstance("TestName", payload.toJson())

            println(service.toJson())

            println("Registering")
            sd.registerService(service)

            println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
            sd.queryForInstance(service.name, service.id) shouldEqual service

            println("Retrieved values: ${sd.queryForInstances(service.name)}")
            sd.queryForInstances(service.name) shouldEqual listOf(service)

            println("Retrieved names: ${sd.queryForNames()}")
            sd.queryForNames().first() shouldEndWith service.id

            println("Updating payload")
            payload.testval = -888
            service.jsonPayload = payload.toJson()
            sd.updateService(service)

            println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
            sd.queryForInstance(service.name, service.id) shouldEqual service

            println("Retrieved values: ${sd.queryForInstances(service.name)}")
            sd.queryForInstances(service.name) shouldEqual listOf(service)

            println("Retrieved names: ${sd.queryForNames()}")
            sd.queryForNames().first() shouldEndWith service.id

            println("Unregistering")
            sd.unregisterService(service)
            sleep(3.seconds)


            sd.queryForNames().size shouldEqual 0
            sd.queryForInstances(service.name).size shouldEqual 0

            invoking { sd.queryForInstance(service.name, service.id) } shouldThrow EtcdRecipeException::class

            try {
                println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
            } catch (e: EtcdRecipeException) {
                println("Exception: $e")
            }
        }

         */
    }
}