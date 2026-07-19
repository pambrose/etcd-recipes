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

package website.spring

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdConnectionConfig
import io.etcd.recipes.common.EtcdRecipes
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.lock.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

// --8<-- [start:service]
// The starter contributes an EtcdRecipes bean, so a service just asks for it. The client
// underneath is shared and closed with the application context.
@Service
class OrderService(
  private val recipes: EtcdRecipes,
) {
  fun processExclusively() {
    recipes.mutex("/locks/orders").use { mutex ->
      mutex.withLock {
        logger.info { "Only one instance of this service runs this at a time" }
      }
    }
  }
}
// --8<-- [end:service]

// --8<-- [start:client-bean]
// Every contributed bean is @ConditionalOnMissingBean, so defining your own Client makes
// the auto-configuration back off and use yours — including for the EtcdRecipes bean and
// the health indicator.
@Configuration
class CustomEtcdConfig {
  @Bean(destroyMethod = "close")
  fun etcdClient(): Client =
    connectToEtcd(
      EtcdConnectionConfig(endpoints = ["http://localhost:2379"], namespace = "/myapp/"),
      // The initReceiver escape hatch: raw jetcd builder options the config does not model.
      // Pass it by name — a trailing lambda would bind to the scoping `block` overload.
      initReceiver = { maxInboundMessageSize(8 * 1024 * 1024) },
    )
}
// --8<-- [end:client-bean]
