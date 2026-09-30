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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

/**
 * [EtcdConnectionConfig] maps onto the jetcd builder (auth / namespace / timeouts asserted via the
 * builder's own getters), and a namespaced client round-trips a key beneath its prefix. Its
 * `toString()` never shows the password, and half-configured mutual TLS is refused.
 */
class EtcdConnectionConfigTests : StringSpec() {
  private val base = "/common/${javaClass.simpleName}"

  init {
    "config maps auth, namespace, and timeouts onto the jetcd builder" {
      val config =
        EtcdConnectionConfig(
          endpoints = ["http://localhost:2379"],
          user = "root",
          password = "secret",
          namespace = "/tenant/",
          connectTimeout = Duration.ofSeconds(3),
          retryMaxDuration = Duration.ofSeconds(7),
        )
      val builder = configuredClientBuilder(config) { this }
      builder.user().asString shouldBe "root"
      builder.password().asString shouldBe "secret"
      builder.namespace().asString shouldBe "/tenant/"
      builder.connectTimeout() shouldBe Duration.ofSeconds(3)
      builder.retryMaxDuration() shouldBe Duration.ofSeconds(7)
    }

    "toString() doesn't show the password" {
      val config = EtcdConnectionConfig(endpoints = ["http://localhost:2379"], user = "root", password = "s3cret")
      val text = config.toString()
      text shouldNotContain "s3cret"
      text shouldContain "user=root"
    }

    "mutual TLS needs both the client certificate and key" {
      shouldThrow<IllegalArgumentException> { EtcdTlsConfig(clientCertPath = "/tls/client.pem") }
      shouldThrow<IllegalArgumentException> { EtcdTlsConfig(clientKeyPath = "/tls/client-key.pem") }
      EtcdTlsConfig(caCertPath = "/tls/ca.pem")
      EtcdTlsConfig(clientCertPath = "/tls/client.pem", clientKeyPath = "/tls/client-key.pem")
    }

    "a namespaced client writes beneath its prefix" {
      val ns = "$base/tenant/"
      connectToEtcd(EtcdConnectionConfig(endpoints = urls, namespace = ns)).use { nsClient ->
        nsClient.putValue("widget", "hello")
      }
      // A raw (un-namespaced) client sees the key at the fully-qualified path.
      connectToEtcd(urls).use { raw ->
        raw.getValue("${ns}widget")?.asString shouldBe "hello"
        raw.deleteKey("${ns}widget")
      }
    }
  }
}
