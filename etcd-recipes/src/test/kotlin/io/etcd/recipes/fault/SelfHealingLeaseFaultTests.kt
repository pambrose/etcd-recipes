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

package io.etcd.recipes.fault

import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Drives real TTL expiry: pausing the etcd container freezes renewals until the
 * client-side lease deadline fires (jetcd's DeadLine service → onCompleted) — the
 * genuine partition-longer-than-TTL path, complementing the out-of-band revoke
 * tests which exercise the NOT_FOUND path.
 */
class SelfHealingLeaseFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "TransientKeyValue key reappears after a partition longer than the TTL" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        val keyPath = "$path/tkv"
        val events = CopyOnWriteArrayList<LeaseEvent>()

        TransientKeyValue(client, keyPath, "value", leaseTtlSecs = 2, autoStart = false).use { tkv ->
          tkv.addLeaseListener { events += it }
          tkv.start()
          pollUntil(10.seconds) { client.isKeyPresent(keyPath) } shouldBe true

          EtcdTestContainer.pause()
          try {
            Thread.sleep(7_000) // well past the 2s TTL: lease expires server-side too
          } finally {
            EtcdTestContainer.unpause()
          }
          EtcdTestContainer.awaitReady()

          pollUntil(60.seconds) { events.any { it is LeaseEvent.Expired } } shouldBe true
          pollUntil(60.seconds) { events.any { it is LeaseEvent.Restored } } shouldBe true
          pollUntil(30.seconds) { client.isKeyPresent(keyPath) } shouldBe true
          tkv.connectionState shouldBe ConnectionState.RECONNECTED
        }
      }
    }
  }
}
