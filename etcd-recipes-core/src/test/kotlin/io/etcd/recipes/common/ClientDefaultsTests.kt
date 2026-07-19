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

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * Guards the resilient client defaults: jetcd 0.8.6 already defaults to
 * waitForReady + gRPC keepalive, but sets no connect timeout and no bound on its
 * internal per-call retries — so a call against an unreachable cluster parked
 * forever. connectToEtcd now bounds both, with user settings taking precedence.
 * No etcd needed.
 */
class ClientDefaultsTests : StringSpec() {
  init {
    "withRecipeDefaults bounds connection establishment and internal retries" {
      val builder = Client.builder().withRecipeDefaults()
      builder.connectTimeout() shouldBe Duration.ofSeconds(5)
      builder.retryMaxDuration() shouldBe Duration.ofSeconds(30)
    }

    "connectToEtcd applies the recipe defaults" {
      val builder = recipeClientBuilder(["http://localhost:2379"]) { this }
      builder.connectTimeout() shouldBe Duration.ofSeconds(5)
      builder.retryMaxDuration() shouldBe Duration.ofSeconds(30)
    }

    "user settings passed via initReceiver win over the recipe defaults" {
      val builder =
        recipeClientBuilder(["http://localhost:2379"]) {
          connectTimeout(Duration.ofSeconds(1)).retryMaxDuration(Duration.ofSeconds(7))
        }
      builder.connectTimeout() shouldBe Duration.ofSeconds(1)
      builder.retryMaxDuration() shouldBe Duration.ofSeconds(7)
    }
  }
}
