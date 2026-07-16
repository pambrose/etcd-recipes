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

import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Drives the RPC retry/timeout layer through a real partition (container pause):
 * bounded policies must fail fast instead of parking forever, and generous
 * policies must ride out the outage.
 */
class ResilientRpcFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "a bounded policy fails with a timeout instead of hanging against a partitioned etcd" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        client.putValue("$path/bounded", "up")

        EtcdTestContainer.pause()
        try {
          val oneShot = RpcResilience(RetryPolicy.never, operationTimeout = 2.seconds)
          val start = TimeSource.Monotonic.markNow()
          shouldThrowAny {
            client.getValue("$path/bounded", "default", oneShot)
          }
          (start.elapsedNow() < 15.seconds) shouldBe true
        } finally {
          EtcdTestContainer.unpause()
        }
        EtcdTestContainer.awaitReady()
      }
    }

    "a generous policy rides out a partition and the call succeeds" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        client.putValue("$path/patient", "survives")

        val result = AtomicReference<String?>(null)
        val error = AtomicReference<Throwable?>(null)
        EtcdTestContainer.pause()
        val reader =
          thread(name = "patient-read") {
            try {
              // Per-attempt timeout of 3s; retries ride out the pause window.
              val patient = RpcResilience(RetryPolicy.bounded(maxAttempts = 20, delay = 500.milliseconds), 3.seconds)
              result.store(client.getValue("$path/patient", "default", patient))
            } catch (e: Throwable) {
              error.store(e)
            }
          }
        try {
          Thread.sleep(4_000)
        } finally {
          EtcdTestContainer.unpause()
        }
        EtcdTestContainer.awaitReady()

        reader.join(60_000)
        error.load() shouldBe null
        result.load() shouldBe "survives"
      }
    }
  }
}
