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

import io.etcd.recipes.common.EtcdConnectionConfig
import io.etcd.recipes.common.EtcdTlsConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Binds `etcd.recipes.*` properties (relaxed binding, so `etcd.recipes.connect-timeout` etc.) and
 * maps them to an [EtcdConnectionConfig]. Constructor-bound and immutable.
 */
@ConfigurationProperties("etcd.recipes")
data class EtcdProperties(
  val endpoints: List<String> = emptyList(),
  val user: String? = null,
  val password: String? = null,
  val namespace: String? = null,
  val connectTimeout: Duration = Duration.ofSeconds(5),
  val retryMaxDuration: Duration = Duration.ofSeconds(30),
  val tls: Tls? = null,
) {
  data class Tls(
    val caCertPath: String? = null,
    val clientCertPath: String? = null,
    val clientKeyPath: String? = null,
  )

  fun toConnectionConfig(): EtcdConnectionConfig =
    EtcdConnectionConfig(
      endpoints = endpoints,
      user = user,
      password = password,
      namespace = namespace,
      connectTimeout = connectTimeout,
      retryMaxDuration = retryMaxDuration,
      tls = tls?.let { EtcdTlsConfig(it.caCertPath, it.clientCertPath, it.clientKeyPath) },
    )
}
