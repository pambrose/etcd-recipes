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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction", "MatchingDeclarationName")

package io.etcd.recipes.ktor

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdConnectionConfig
import io.etcd.recipes.common.EtcdRecipes
import io.etcd.recipes.common.connectToEtcd
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey

/**
 * Configuration for [EtcdPlugin]. Either supply the connection fields (the plugin builds and owns
 * the client, closing it on application stop) or inject a pre-built [client] the plugin will not
 * close (you own its lifecycle).
 */
class EtcdPluginConfig {
  var endpoints: List<String> = []
  var user: String? = null
  var password: String? = null
  var namespace: String? = null
  var client: Client? = null

  // Test/advanced seam for the plugin-owned client; defaults to building from the config.
  internal var clientFactory: (EtcdConnectionConfig) -> Client = { connectToEtcd(it) }

  internal fun toConnectionConfig(): EtcdConnectionConfig =
    EtcdConnectionConfig(endpoints = endpoints, user = user, password = password, namespace = namespace)
}

private val EtcdClientKey = AttributeKey<Client>("EtcdRecipesClient")

/**
 * A Ktor plugin that connects to etcd for the application's lifetime. Install it to get an
 * [Application.etcdClient] (and [Application.etcdRecipes] factory); a plugin-owned client is closed
 * automatically on `ApplicationStopping`.
 */
val EtcdPlugin =
  createApplicationPlugin("Etcd", ::EtcdPluginConfig) {
    val supplied = pluginConfig.client
    val client = supplied ?: pluginConfig.clientFactory(pluginConfig.toConnectionConfig())
    application.attributes.put(EtcdClientKey, client)

    // Only close what the plugin created; an injected client stays the caller's responsibility.
    if (supplied == null)
      on(MonitoringEvent(ApplicationStopping)) { app -> app.attributes[EtcdClientKey].close() }
  }

/** The etcd [Client] installed by [EtcdPlugin]. */
val Application.etcdClient: Client get() = attributes[EtcdClientKey]

/** An [EtcdRecipes] factory bound to the [etcdClient]. */
val Application.etcdRecipes: EtcdRecipes get() = EtcdRecipes(etcdClient)
