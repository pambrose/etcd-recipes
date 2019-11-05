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
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.urls
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldEndWith
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import kotlin.time.seconds

class SerialServiceDiscoveryTests {
    val path = "/discovery/${javaClass.simpleName}"

    @Test
    fun badArgsTest() {
        invoking { ServiceDiscovery(urls, "") } shouldThrow IllegalArgumentException::class
        invoking { ServiceDiscovery(emptyList(), "something") } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun discoveryTest() {
        ServiceDiscovery(urls, path)
            .use { sd ->

                val payload = TestPayload(-999)
                val service = ServiceInstance("TestName", payload.toJson())

                logger.info { service.toJson() }

                logger.info { "Registering" }
                sd.registerService(service)

                logger.info { "Retrieved value: ${sd.queryForInstance(service.name, service.id)}" }
                sd.queryForInstance(service.name, service.id) shouldEqual service

                logger.info { "Retrieved values: ${sd.queryForInstances(service.name)}" }
                sd.queryForInstances(service.name) shouldEqual listOf(service)

                logger.info { "Retrieved names: ${sd.queryForNames()}" }
                sd.queryForNames().first() shouldEndWith service.id

                logger.info { "Updating payload" }
                payload.testval = -888
                service.jsonPayload = payload.toJson()
                sd.updateService(service)

                logger.info { "Retrieved value: ${sd.queryForInstance(service.name, service.id)}" }
                sd.queryForInstance(service.name, service.id) shouldEqual service

                logger.info { "Retrieved values: ${sd.queryForInstances(service.name)}" }
                sd.queryForInstances(service.name) shouldEqual listOf(service)

                logger.info { "Retrieved names: ${sd.queryForNames()}" }
                sd.queryForNames().first() shouldEndWith service.id

                logger.info { "Unregistering" }
                sd.unregisterService(service)
                sleep(3.seconds)


                sd.queryForNames().size shouldEqual 0
                sd.queryForInstances(service.name).size shouldEqual 0

                invoking { sd.queryForInstance(service.name, service.id) } shouldThrow EtcdRecipeException::class

                try {
                    logger.info { "Retrieved value: ${sd.queryForInstance(service.name, service.id)}" }
                } catch (e: EtcdRecipeException) {
                    logger.info { "Exception: $e" }
                }
            }
    }

    companion object : KLogging()
}