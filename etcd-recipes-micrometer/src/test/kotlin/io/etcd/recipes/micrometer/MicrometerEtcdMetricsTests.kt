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

package io.etcd.recipes.micrometer

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Micrometer backend for [io.etcd.recipes.common.EtcdMetrics], verified against a
 * [SimpleMeterRegistry] — both directly and installed end-to-end via
 * [ResilienceConfig.withMetrics].
 */
class MicrometerEtcdMetricsTests : StringSpec() {
  init {
    "recordRpc registers a timer tagged by operation (without the key) and outcome" {
      val registry = SimpleMeterRegistry()
      MicrometerEtcdMetrics(registry).recordRpc("getResponse(/a/b)", 5.milliseconds, attempts = 1, failed = false)

      val timer = registry.find("etcd.rpc").tag("operation", "getResponse").tag("outcome", "success").timer()
      timer.shouldNotBeNull()
      timer.count() shouldBe 1L
    }

    "recordRpc counts extra attempts as retries" {
      val registry = SimpleMeterRegistry()
      MicrometerEtcdMetrics(registry).recordRpc("putValue(/x)", 10.milliseconds, attempts = 3, failed = true)

      registry.find("etcd.rpc").tag("outcome", "failure").timer().shouldNotBeNull()
      registry.find("etcd.rpc.retries").tag("operation", "putValue").counter()!!.count() shouldBe 2.0
    }

    "watch-recovery and keep-alive events increment counters tagged by kind" {
      val registry = SimpleMeterRegistry()
      val metrics = MicrometerEtcdMetrics(registry)
      metrics.incrementWatchRecovery("resubscribed", "/k")
      metrics.incrementKeepAlive("renewal", 7L)

      registry.find("etcd.watch.recovery").tag("kind", "resubscribed").counter()!!.count() shouldBe 1.0
      registry.find("etcd.keepalive").tag("kind", "renewal").counter()!!.count() shouldBe 1.0
    }

    "installed via ResilienceConfig.withMetrics it records real RPC activity" {
      val registry = SimpleMeterRegistry()
      val client: Client =
        mockk {
          every { kvClient } returns
            mockk<KV> {
              every { get(any<ByteSequence>(), any<GetOption>()) } returns
                CompletableFuture.completedFuture(
                  mockk<GetResponse> {
                    every { kvs } returns emptyList()
                    every { isMore } returns false
                  },
                )
            }
        }

      val rpc = ResilienceConfig.DEFAULT.withMetrics(MicrometerEtcdMetrics(registry)).rpc
      client.getResponse("/probe", getOption { withCountOnly(true) }, rpc)

      registry.find("etcd.rpc").tag("operation", "getResponse").tag("outcome", "success").timer()!!.count() shouldBe 1L
    }
  }
}
