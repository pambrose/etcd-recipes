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

package io.etcd.recipes.ktor

import io.etcd.jetcd.Client
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** [EtcdPlugin] installs the client onto the application and closes only a plugin-owned client on stop. */
class EtcdPluginTests : StringSpec() {
  init {
    "installs a plugin-owned client and closes it on stop" {
      val client = mockk<Client>(relaxed = true)
      testApplication {
        application {
          install(EtcdPlugin) { clientFactory = { client } }
          etcdClient shouldBe client
        }
        startApplication()
      }
      verify { client.close() }
    }

    "a user's ApplicationStopping teardown can still use the plugin-owned client" {
      val closed = AtomicBoolean(false)
      val client = mockk<Client>(relaxed = true) { every { close() } answers { closed.store(true) } }
      val closedDuringTeardown = AtomicReference<Boolean?>(null)
      testApplication {
        application {
          install(EtcdPlugin) { clientFactory = { client } }
          // Registered after the plugin, as an app's own teardown usually is
          monitor.subscribe(ApplicationStopping) { closedDuringTeardown.store(closed.load()) }
        }
        startApplication()
      }
      withClue("the client was closed before the app's ApplicationStopping teardown ran") {
        closedDuringTeardown.load() shouldBe false
      }
      closed.load() shouldBe true
    }

    "does not close an injected client on stop" {
      val client = mockk<Client>(relaxed = true)
      testApplication {
        application {
          install(EtcdPlugin) { this.client = client }
          etcdClient shouldBe client
        }
        startApplication()
      }
      verify(exactly = 0) { client.close() }
    }
  }
}
