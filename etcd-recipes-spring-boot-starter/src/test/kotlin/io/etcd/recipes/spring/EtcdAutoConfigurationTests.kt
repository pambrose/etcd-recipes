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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.File
import java.time.Duration

/**
 * The auto-configuration wires a [Client] + [EtcdRecipes] from properties (jetcd's `build()` is
 * lazy, so no etcd is needed), yields to a user-supplied client, and contributes the health
 * indicator only with Actuator present. Every property binds, the starter carries the
 * kotlin-reflect that binding needs, and the bound password never shows in `toString()`.
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

    "management.health.etcd.enabled=false turns the health indicator off" {
      runner
        .withPropertyValues("etcd.recipes.endpoints=http://localhost:2379", "management.health.etcd.enabled=false")
        .run { context -> context.getBeansOfType(HealthIndicator::class.java).size shouldBe 0 }
    }

    "etcd.recipes.health.timeout binds the health probe's timeout" {
      runner
        .withPropertyValues("etcd.recipes.endpoints=http://localhost:2379", "etcd.recipes.health.timeout=500ms")
        .run { context ->
          context.getBean(EtcdProperties::class.java).health.timeout shouldBe Duration.ofMillis(500)
        }
    }

    "every etcd.recipes property binds" {
      runner
        // A supplied client: building one from these TLS paths would read files that don't exist
        .withBean("myClient", Client::class.java, { mockk<Client>(relaxed = true) })
        .withPropertyValues(
          "etcd.recipes.endpoints=http://a:2379,http://b:2379",
          "etcd.recipes.user=root",
          "etcd.recipes.password=s3cret",
          "etcd.recipes.namespace=/tenant/",
          "etcd.recipes.connect-timeout=3s",
          "etcd.recipes.retry-max-duration=7s",
          "etcd.recipes.tls.ca-cert-path=/tls/ca.pem",
          "etcd.recipes.tls.client-cert-path=/tls/client.pem",
          "etcd.recipes.tls.client-key-path=/tls/client-key.pem",
        ).run { context ->
          val properties = context.getBean(EtcdProperties::class.java)
          properties.endpoints shouldBe ["http://a:2379", "http://b:2379"]
          properties.user shouldBe "root"
          properties.password shouldBe "s3cret"
          properties.namespace shouldBe "/tenant/"
          properties.connectTimeout shouldBe Duration.ofSeconds(3)
          properties.retryMaxDuration shouldBe Duration.ofSeconds(7)
          properties.tls shouldBe EtcdProperties.Tls("/tls/ca.pem", "/tls/client.pem", "/tls/client-key.pem")
        }
    }

    "the starter depends on kotlin-reflect itself, not through another library" {
      // Binding the all-defaults Kotlin properties needs kotlin-reflect (see EtcdProperties).
      // Arriving only transitively, it would vanish with that library, and the properties
      // would silently stop binding.
      withClue("kotlin-reflect isn't a direct dependency of the starter") {
        File("build.gradle.kts").readText() shouldContain "libs.kotlin.reflect"
      }
    }

    "the bound password doesn't show in toString()" {
      val text = EtcdProperties(endpoints = ["http://localhost:2379"], user = "root", password = "s3cret").toString()
      text shouldNotContain "s3cret"
      text shouldContain "user=root"
    }

    "half-configured mutual TLS fails startup instead of connecting without a client certificate" {
      runner
        .withPropertyValues(
          "etcd.recipes.endpoints=http://localhost:2379",
          "etcd.recipes.tls.client-cert-path=/tls/c.pem",
        )
        .run { context ->
          val failure = context.startupFailure.shouldNotBeNull()
          generateSequence(failure) { it.cause }.any { it is IllegalArgumentException } shouldBe true
        }
    }

    "starts without Actuator, and without a health indicator" {
      runner
        .withClassLoader(FilteredClassLoader(HealthIndicator::class.java))
        .withPropertyValues("etcd.recipes.endpoints=http://localhost:2379")
        .run { context ->
          context.startupFailure shouldBe null
          context.getBeansOfType(Client::class.java).size shouldBe 1
          context.containsBean("etcdHealthIndicator") shouldBe false
        }
    }

    "a user-supplied Client bean is not overridden" {
      runner
        .withBean("myClient", Client::class.java, { mockk<Client>(relaxed = true) })
        .run { context ->
          context.getBeansOfType(Client::class.java).keys.toList() shouldContainExactly ["myClient"]
        }
    }
  }
}
