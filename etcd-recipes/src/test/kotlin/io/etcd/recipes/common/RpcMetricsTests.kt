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

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.GetOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * The metrics SPI threading ([ResilienceConfig.withMetrics]) and RPC-funnel instrumentation
 * ([retryRpc]) — verified without etcd via a recording [EtcdMetrics] and a mocked client.
 */
class RpcMetricsTests : StringSpec() {
  private class RecordingMetrics : EtcdMetrics {
    data class Rpc(
      val opName: String,
      val attempts: Int,
      val failed: Boolean,
    )

    val rpcs = CopyOnWriteArrayList<Rpc>()

    override fun recordRpc(
      opName: String,
      duration: Duration,
      attempts: Int,
      failed: Boolean,
    ) {
      rpcs += Rpc(opName, attempts, failed)
    }
  }

  private fun clientThatGets(succeeds: Boolean): Client =
    mockk {
      every { kvClient } returns
        mockk<KV> {
          if (succeeds) {
            every { get(any<ByteSequence>(), any<GetOption>()) } returns
              CompletableFuture.completedFuture(
                mockk<GetResponse> {
                  every { kvs } returns emptyList()
                  every { isMore } returns false
                },
              )
          } else {
            every { get(any<ByteSequence>(), any<GetOption>()) } throws IllegalStateException("etcd down")
          }
        }
    }

  init {
    "ResilienceConfig.withMetrics propagates the sink to every sub-config" {
      val m = RecordingMetrics()
      val cfg = ResilienceConfig.DEFAULT.withMetrics(m)
      cfg.metrics shouldBe m
      cfg.rpc.metrics shouldBe m
      cfg.watch.metrics shouldBe m
      cfg.lease.metrics shouldBe m
      ResilienceConfig.DEFAULT.metrics shouldBe EtcdMetrics.NoOp
    }

    "a successful RPC records recordRpc once with failed=false" {
      val m = RecordingMetrics()
      clientThatGets(succeeds = true)
        .getResponse("k", getOption { withCountOnly(true) }, RpcResilience.DEFAULT.withMetrics(m))
      m.rpcs.size shouldBe 1
      m.rpcs.first() shouldBe RecordingMetrics.Rpc("getResponse(k)", attempts = 1, failed = false)
    }

    "a non-retriable RPC failure records recordRpc with failed=true and rethrows" {
      val m = RecordingMetrics()
      shouldThrow<IllegalStateException> {
        clientThatGets(succeeds = false)
          .getResponse("k", getOption { withCountOnly(true) }, RpcResilience.DEFAULT.withMetrics(m))
      }
      m.rpcs.size shouldBe 1
      m.rpcs.first().failed shouldBe true
    }
  }
}
