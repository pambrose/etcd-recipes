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

package io.etcd.recipes.common

import java.time.Duration

/**
 * TLS material for [EtcdConnectionConfig]. All paths are files on disk. [caCertPath] sets the trust
 * manager (server verification); [clientCertPath] + [clientKeyPath] together enable mutual TLS,
 * so setting only one of them is refused rather than silently connecting without a client
 * certificate.
 */
data class EtcdTlsConfig(
  val caCertPath: String? = null,
  val clientCertPath: String? = null,
  val clientKeyPath: String? = null,
) {
  init {
    require((clientCertPath == null) == (clientKeyPath == null)) {
      "Mutual TLS needs both clientCertPath and clientKeyPath, or neither"
    }
  }
}

/**
 * A declarative etcd connection, mapped onto the jetcd client builder by
 * [connectToEtcd(config)][connectToEtcd]. Surfaces the common options — endpoints, auth, key
 * [namespace] (multi-tenancy), TLS, and timeouts — as plain values, so callers no longer need the
 * `initReceiver` escape hatch for them. The integration modules (Spring Boot starter, Ktor plugin)
 * bind their own config to this type.
 */
data class EtcdConnectionConfig(
  val endpoints: List<String>,
  val user: String? = null,
  val password: String? = null,
  val namespace: String? = null,
  val connectTimeout: Duration = Duration.ofSeconds(5),
  val retryMaxDuration: Duration = Duration.ofSeconds(30),
  val tls: EtcdTlsConfig? = null,
) {
  // The password never shows: a logged or printed config must not leak the credential
  override fun toString(): String =
    "EtcdConnectionConfig(endpoints=$endpoints, user=$user, password=${password?.let { REDACTED }}, " +
      "namespace=$namespace, connectTimeout=$connectTimeout, retryMaxDuration=$retryMaxDuration, tls=$tls)"

  internal companion object {
    const val REDACTED = "****"
  }
}
