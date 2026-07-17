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

package website.ktor

import io.etcd.jetcd.Client
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.ktor.EtcdPlugin
import io.etcd.recipes.ktor.etcdClient
import io.etcd.recipes.ktor.etcdRecipes
import io.etcd.recipes.lock.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.install

private val logger = KotlinLogging.logger {}

fun Application.installPlugin() {
  // --8<-- [start:install]
  install(EtcdPlugin) {
    endpoints = listOf("http://localhost:2379")
    namespace = "/myapp/"
  }
  // --8<-- [end:install]
}

fun Application.installWithAuth() {
  // --8<-- [start:auth]
  install(EtcdPlugin) {
    endpoints = listOf("http://etcd-1:2379", "http://etcd-2:2379")
    user = "orders-svc"
    password = System.getenv("ETCD_PASSWORD")
    namespace = "/myapp/"
  }
  // --8<-- [end:auth]
}

fun Application.installOwnClient() {
  // --8<-- [start:own-client]
  // Injecting a client makes the plugin a pure consumer: it will NOT close this client on
  // ApplicationStopping, because it did not create it. Closing it stays your job.
  val myClient = connectToEtcd(listOf("http://localhost:2379"))

  install(EtcdPlugin) {
    client = myClient
  }
  // --8<-- [end:own-client]
}

fun Application.consumeClient() {
  // --8<-- [start:consume-client]
  // The raw jetcd client the plugin installed, for the extension-level API.
  val client: Client = etcdClient
  logger.info { "Connected: $client" }
  // --8<-- [end:consume-client]
}

fun Application.consumeRecipes() {
  // --8<-- [start:consume-recipes]
  // A factory bound to the plugin's client. Each call news up a recipe; you own its
  // start()/close() lifecycle, so scope it to the work rather than to the application.
  etcdRecipes.mutex("/locks/orders").use { mutex ->
    mutex.withLock {
      logger.info { "One instance of this service at a time" }
    }
  }
  // --8<-- [end:consume-recipes]
}
