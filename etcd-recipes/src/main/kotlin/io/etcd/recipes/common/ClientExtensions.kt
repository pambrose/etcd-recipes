/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

@JvmOverloads
fun connectToEtcd(urls: List<String>, initReciever: ClientBuilder.() -> ClientBuilder = { this }): Client {
  require(urls.isNotEmpty()) { "URLs cannot be empty" }
  return etcdClient { endpoints(*urls.toTypedArray()).initReciever() }
}

@JvmOverloads
fun <T> connectToEtcd(
  urls: List<String>,
  initReciever: ClientBuilder.() -> ClientBuilder = { this },
  block: (client: Client) -> T
): T = connectToEtcd(urls, initReciever).use { block(it) }