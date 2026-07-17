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

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.GetResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.health.contributor.Status
import java.util.concurrent.CompletableFuture

/** The health indicator maps the client's reachability probe to Actuator UP / DOWN. */
class EtcdHealthIndicatorTests : StringSpec() {
  private fun clientWhoseProbe(future: CompletableFuture<GetResponse>): Client {
    val kv = mockk<KV>()
    every { kv.get(any(), any()) } returns future
    return mockk<Client> { every { kvClient } returns kv }
  }

  init {
    "reports UP when the probe succeeds" {
      val client = clientWhoseProbe(CompletableFuture.completedFuture(mockk<GetResponse>(relaxed = true)))
      EtcdHealthIndicator(client).health().status shouldBe Status.UP
    }

    "reports DOWN when the probe fails" {
      val client = clientWhoseProbe(CompletableFuture.failedFuture(RuntimeException("unreachable")))
      EtcdHealthIndicator(client).health().status shouldBe Status.DOWN
    }
  }
}
