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
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlin.time.Duration.Companion.seconds

class SerialServiceDiscoveryTests : StringSpec() {
    val path = "/discovery/${javaClass.simpleName}"

    init {
        "badArgsTest" {
            connectToEtcd(urls) { client ->
                shouldThrow<IllegalArgumentException> { ServiceDiscovery(client, "") }
            }
        }

        "discoveryTest" {
            connectToEtcd(urls) { client ->
                withServiceDiscovery(client, path) {
                    val payload = TestPayload(-999)
                    val service = ServiceInstance("TestName", payload.toJson())

                    logger.debug { service.toJson() }

                    logger.debug { "Registering" }
                    registerService(service)

                    logger.debug { "Retrieved value: ${queryForInstance(service.name, service.id)}" }
                    queryForInstance(service.name, service.id) shouldBe service

                    logger.debug { "Retrieved values: ${queryForInstances(service.name)}" }
                    queryForInstances(service.name) shouldBe [service]

                    logger.debug { "Retrieved names: ${queryForNames()}" }
                    queryForNames().first() shouldEndWith service.id

                    logger.debug { "Updating payload" }
                    payload.testval = -888
                    service.jsonPayload = payload.toJson()
                    updateService(service)

                    logger.debug { "Retrieved value: ${queryForInstance(service.name, service.id)}" }
                    queryForInstance(service.name, service.id) shouldBe service

                    logger.debug { "Retrieved values: ${queryForInstances(service.name)}" }
                    queryForInstances(service.name) shouldBe [service]

                    logger.debug { "Retrieved names: ${queryForNames()}" }
                    queryForNames().first() shouldEndWith service.id

                    logger.debug { "Unregistering" }
                    unregisterService(service)
                    sleep(3.seconds)

                    queryForNames().size shouldBe 0
                    queryForInstances(service.name).size shouldBe 0

                    shouldThrow<EtcdRecipeException> { queryForInstance(service.name, service.id) }

                    try {
                        logger.debug { "Retrieved value: ${queryForInstance(service.name, service.id)}" }
                    } catch (e: EtcdRecipeException) {
                        logger.debug { "Exception: $e" }
                    }
                }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
