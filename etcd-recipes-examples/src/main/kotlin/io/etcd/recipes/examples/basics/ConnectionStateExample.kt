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

package io.etcd.recipes.examples.basics

import com.pambrose.common.util.sleep
import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates connection-state listeners: a TransientKeyValue derives
 * CONNECTED / SUSPENDED / RECONNECTED / LOST from its own lease stream. Kill and
 * restart the local etcd (`./etcd.sh`) while this runs — a short outage reports
 * SUSPENDED then RECONNECTED; one longer than the TTL reports LOST (the lease
 * expired; the key is healed) then RECONNECTED.
 */
fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")

  connectToEtcd(urls) { client ->
    TransientKeyValue(client, "/examples/connection-state", "up", leaseTtlSecs = 2).use { tkv ->
      tkv.addConnectionStateListener { new, prev ->
        logger.info { "Connection state: $prev -> $new" }
        if (new == ConnectionState.LOST) logger.warn { "Ownership may have been lost during the outage" }
      }
      repeat(120) {
        sleep(2.seconds)
        logger.info { "state=${tkv.connectionState}" }
      }
    }
  }
}
