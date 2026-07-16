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

package io.etcd.recipes.lock

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.WatchOption
import io.etcd.recipes.common.ResilienceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Anchoring contract for [WaiterSupport]. A DELETE-watch created without a start revision
 * begins at whatever the store revision happens to be when etcd processes the create — so a
 * DELETE that lands in the gap between the pre-live recheck and the watch going live is lost
 * by both, and an unbounded `lock()` parks forever. The fix is to anchor the watch at the
 * revision at which the awaited predecessor was last observed present, guaranteeing every
 * later DELETE is (re)delivered. These tests pin that the subscribed [WatchOption] carries
 * `observedRevision + 1`.
 *
 * jetcd's [Watch] is mocked (in the [io.etcd.recipes.common.ResilientWatcherTests] style) so
 * the option handed to the subscribe call can be captured; no etcd connection is required. An
 * already-open latch plus a null deadline lets `await` return the instant the subscribe is
 * recorded.
 */
class WaiterSupportTests : StringSpec() {
  /** Captures the [WatchOption] of every subscribe the watcher under test makes. */
  private class WatchMocks {
    val options = CopyOnWriteArrayList<WatchOption>()

    val watch: Watch =
      mockk {
        every { watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) } answers {
          options += secondArg<WatchOption>()
          mockk<Watch.Watcher>(relaxed = true)
        }
      }

    val client: Client = mockk { every { watchClient } returns watch }
  }

  init {
    "awaitKeyDeletion anchors the DELETE watch at observedRevision + 1" {
      val mocks = WatchMocks()
      WaiterSupport.awaitKeyDeletion(
        mocks.client,
        "/lock/write-0001",
        ResilienceConfig.DEFAULT,
        CountDownLatch(0),
        deadline = null,
        observedRevision = 100L,
        reportRecovery = {},
        recordException = {},
      )
      mocks.options.size shouldBe 1
      mocks.options[0].revision shouldBe 101L
      mocks.options[0].isNoPut shouldBe true
    }

    "awaitKeyDeletion with observedRevision 0 leaves the watch un-anchored" {
      val mocks = WatchMocks()
      WaiterSupport.awaitKeyDeletion(
        mocks.client,
        "/lock/write-0001",
        ResilienceConfig.DEFAULT,
        CountDownLatch(0),
        deadline = null,
        observedRevision = 0L,
        reportRecovery = {},
        recordException = {},
      )
      mocks.options.size shouldBe 1
      mocks.options[0].revision shouldBe 0L
    }

    "awaitPrefixDeletion anchors the prefix DELETE watch at observedRevision + 1" {
      val mocks = WatchMocks()
      WaiterSupport.awaitPrefixDeletion(
        mocks.client,
        "/sem/holders/",
        ResilienceConfig.DEFAULT,
        CountDownLatch(0),
        deadline = null,
        observedRevision = 200L,
        shouldWake = { false },
        reportRecovery = {},
        recordException = {},
      )
      mocks.options.size shouldBe 1
      mocks.options[0].revision shouldBe 201L
      mocks.options[0].isPrefix shouldBe true
      mocks.options[0].isNoPut shouldBe true
    }
  }
}
