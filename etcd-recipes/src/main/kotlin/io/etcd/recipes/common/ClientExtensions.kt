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
import java.time.Duration

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
