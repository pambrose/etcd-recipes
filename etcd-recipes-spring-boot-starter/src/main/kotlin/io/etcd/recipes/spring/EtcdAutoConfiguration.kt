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

package io.etcd.recipes.spring

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdRecipes
import io.etcd.recipes.common.connectToEtcd
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configures a jetcd [Client] (and an [EtcdRecipes] factory) from `etcd.recipes.*` properties,
 * so a Spring Boot app connects with a few lines of `application.yml`. `@ConditionalOnClass(Client)`
 * keeps it inert unless the library is present; `@ConditionalOnMissingBean` lets an app override any
 * bean. The client bean's `destroyMethod = "close"` gives graceful shutdown on context teardown.
 */
@AutoConfiguration
@EnableConfigurationProperties(EtcdProperties::class)
@ConditionalOnClass(Client::class)
class EtcdAutoConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  fun etcdClient(properties: EtcdProperties): Client = connectToEtcd(properties.toConnectionConfig())

  @Bean
  @ConditionalOnMissingBean
  fun etcdRecipes(client: Client): EtcdRecipes = EtcdRecipes(client)

  /** Contributed only when Actuator's [HealthIndicator] is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(HealthIndicator::class)
  class EtcdHealthConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["etcdHealthIndicator"])
    fun etcdHealthIndicator(client: Client): HealthIndicator = EtcdHealthIndicator(client)
  }
}
