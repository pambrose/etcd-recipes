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

package io.etcd.recipes.barrier

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** A double barrier's `clientId` must name its waiters; it was accepted but never used. */
class DistributedDoubleBarrierClientIdTests : StringSpec() {
  private val base = "/barriers/${javaClass.simpleName}"

  init {
    "a double barrier's clientId names its waiting keys" {
      connectToEtcd(urls) { client ->
        val path = "$base/client-id"
        client.deleteChildren(path)
        DistributedDoubleBarrier(client, path, memberCount = 2, clientId = "double-X").use { barrier ->
          thread(isDaemon = true) { runCatching { barrier.enter(10, TimeUnit.SECONDS) } }
          val waiting = "$path/enter/waiting"
          pollUntil(10.seconds) { client.getChildrenKeys(waiting).isNotEmpty() } shouldBe true
          client.getChildrenKeys(waiting).single().substringAfterLast('/') shouldStartWith "double-X:"
        }
        client.deleteChildren(path)
      }
    }
  }
}
