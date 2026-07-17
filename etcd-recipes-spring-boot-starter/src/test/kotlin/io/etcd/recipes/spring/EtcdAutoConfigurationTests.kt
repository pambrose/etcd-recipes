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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

/**
 * The auto-configuration wires a [Client] + [EtcdRecipes] from properties (jetcd's `build()` is
 * lazy, so no etcd is needed), yields to a user-supplied client, and contributes the health
 * indicator only with Actuator present.
 */
class EtcdAutoConfigurationTests : StringSpec() {
  private val runner =
    ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(EtcdAutoConfiguration::class.java))

  init {
    "creates Client, EtcdRecipes, and health indicator beans from properties" {
      runner
        .withPropertyValues("etcd.recipes.endpoints=http://localhost:2379")
        .run { context ->
          context.getBeansOfType(Client::class.java).size shouldBe 1
          context.getBeansOfType(EtcdRecipes::class.java).size shouldBe 1
          context.getBeansOfType(HealthIndicator::class.java).size shouldBe 1
        }
    }

    "a user-supplied Client bean is not overridden" {
      runner
        .withBean("myClient", Client::class.java, Supplier { mockk<Client>(relaxed = true) })
        .run { context ->
          context.getBeansOfType(Client::class.java).keys.toList() shouldContainExactly listOf("myClient")
        }
    }
  }
}
