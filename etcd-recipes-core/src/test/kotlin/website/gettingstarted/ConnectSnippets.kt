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

package website.gettingstarted

import io.etcd.recipes.common.EtcdConnectionConfig
import io.etcd.recipes.common.EtcdTlsConfig
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.ping
import io.etcd.recipes.common.putValue
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

private val logger = KotlinLogging.logger {}

fun connectBasic() {
  // --8<-- [start:basic]
  // `use` closes the client; the recipes never take ownership of it for you.
  connectToEtcd(listOf("http://localhost:2379")).use { client ->
    client.putValue("/greeting", "hello")
    logger.info { client.getValue("/greeting", "<absent>") }
  }
  // --8<-- [end:basic]
}

fun connectScoped() {
  // --8<-- [start:scoped]
  // The block form closes the client when the block returns, even on a throw.
  val greeting =
    connectToEtcd(listOf("http://localhost:2379")) { client ->
      client.getValue("/greeting", "<absent>")
    }
  logger.info { greeting }
  // --8<-- [end:scoped]
}

fun connectWithConfig() {
  // --8<-- [start:config]
  val config =
    EtcdConnectionConfig(
      endpoints = listOf("https://etcd-1:2379", "https://etcd-2:2379"),
      user = "app",
      password = System.getenv("ETCD_PASSWORD"),
      // Every key this client touches is transparently prefixed with /prod.
      namespace = "/prod",
      // Note: java.time.Duration here, not kotlin.time.Duration.
      connectTimeout = Duration.ofSeconds(5),
      retryMaxDuration = Duration.ofSeconds(30),
      tls =
        EtcdTlsConfig(
          caCertPath = "/etc/etcd/ca.crt",
          clientCertPath = "/etc/etcd/client.crt",
          clientKeyPath = "/etc/etcd/client.key",
        ),
    )

  connectToEtcd(config).use { client ->
    logger.info { "Reachable: ${client.ping()}" }
  }
  // --8<-- [end:config]
}

fun connectWithBuilder() {
  // --8<-- [start:builder]
  // Drop to the jetcd ClientBuilder for anything the config class doesn't cover.
  connectToEtcd(
    urls = listOf("http://localhost:2379"),
    initReceiver = { maxInboundMessageSize(8 * 1024 * 1024) },
  ).use { client ->
    logger.info { "Connected" }
  }
  // --8<-- [end:builder]
}

fun pingEtcd() {
  // --8<-- [start:ping]
  connectToEtcd(listOf("http://localhost:2379")).use { client ->
    // An active, count-only GET. Cheap enough for a health endpoint.
    if (!client.ping()) logger.error { "etcd is not reachable" }
  }
  // --8<-- [end:ping]
}
