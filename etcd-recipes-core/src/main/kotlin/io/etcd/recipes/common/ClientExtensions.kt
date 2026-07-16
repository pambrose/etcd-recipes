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

@file:JvmName("ClientUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.ClientBuilder
import java.io.File
import java.time.Duration

private const val PING_PROBE_KEY = "health-check-probe"

/**
 * Recipe-tuned client defaults on top of jetcd's own (jetcd 0.8.6 already enables
 * waitForReady, gRPC keepalive at 30s/10s, and a 500ms→2.5s retry backoff): bound
 * connection establishment and jetcd's internal per-call retries, so calls against
 * an unreachable cluster fail instead of parking forever.
 */
fun ClientBuilder.withRecipeDefaults(): ClientBuilder =
  connectTimeout(Duration.ofSeconds(5))
    .retryMaxDuration(Duration.ofSeconds(30))

// Defaults are applied BEFORE initReceiver so user settings win. internal (not
// private) so tests can inspect the builder without connecting.
internal fun recipeClientBuilder(
  urls: List<String>,
  initReceiver: ClientBuilder.() -> ClientBuilder,
): ClientBuilder {
  require(urls.isNotEmpty()) { "URLs cannot be empty" }
  return Client.builder().endpoints(*urls.toTypedArray()).withRecipeDefaults().initReceiver()
}

@JvmOverloads
fun connectToEtcd(
  urls: List<String>,
  initReceiver: ClientBuilder.() -> ClientBuilder = { this },
): Client = recipeClientBuilder(urls, initReceiver).build()

@JvmOverloads
fun <T> connectToEtcd(
  urls: List<String>,
  initReceiver: ClientBuilder.() -> ClientBuilder = { this },
  block: (client: Client) -> T,
): T = connectToEtcd(urls, initReceiver).use { block(it) }

// Maps an EtcdConnectionConfig onto the jetcd builder. Applied AFTER withRecipeDefaults so
// config values win; jetcd's sslContext(Consumer) form lets jetcd apply the gRPC/ALPN specifics
// while we only supply trust/key material. internal so tests can inspect the builder.
internal fun ClientBuilder.applyConfig(config: EtcdConnectionConfig): ClientBuilder {
  connectTimeout(config.connectTimeout)
  retryMaxDuration(config.retryMaxDuration)
  config.user?.let { user(it.asByteSequence) }
  config.password?.let { password(it.asByteSequence) }
  config.namespace?.let { namespace(it.asByteSequence) }
  config.tls?.let { tls ->
    sslContext { builder ->
      tls.caCertPath?.let { builder.trustManager(File(it)) }
      if (tls.clientCertPath != null && tls.clientKeyPath != null)
        builder.keyManager(File(tls.clientCertPath), File(tls.clientKeyPath))
    }
  }
  return this
}

internal fun configuredClientBuilder(
  config: EtcdConnectionConfig,
  initReceiver: ClientBuilder.() -> ClientBuilder,
): ClientBuilder {
  require(config.endpoints.isNotEmpty()) { "Endpoints cannot be empty" }
  return Client.builder()
    .endpoints(*config.endpoints.toTypedArray())
    .withRecipeDefaults()
    .applyConfig(config)
    .initReceiver()
}

@JvmOverloads
fun connectToEtcd(
  config: EtcdConnectionConfig,
  initReceiver: ClientBuilder.() -> ClientBuilder = { this },
): Client = configuredClientBuilder(config, initReceiver).build()

@JvmOverloads
fun <T> connectToEtcd(
  config: EtcdConnectionConfig,
  initReceiver: ClientBuilder.() -> ClientBuilder = { this },
  block: (client: Client) -> T,
): T = connectToEtcd(config, initReceiver).use { block(it) }

/**
 * Active reachability probe on a bare [Client]: a bounded, non-mutating count-only GET through the
 * RPC retry/timeout funnel. Returns false instead of throwing when etcd cannot be reached (or the
 * client is closed) — the [Client]-level counterpart to [EtcdConnector.ping].
 */
@JvmOverloads
fun Client.ping(rpc: RpcResilience = RpcResilience.DEFAULT): Boolean =
  runCatching { getResponse(PING_PROBE_KEY, getOption { withCountOnly(true) }, rpc) }.isSuccess
