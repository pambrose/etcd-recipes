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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Covers the observability surface added to [EtcdConnector] (idea #7 phase 1): the
 * push-based background-exception callback, the passive [EtcdConnector.isHealthy]
 * signal, and the active [EtcdConnector.ping] reachability probe.
 */
class EtcdConnectorObservabilityTests : StringSpec() {
  /** Exposes the protected reporting hooks so tests can drive state deterministically. */
  private class TestConnector(
    client: Client,
  ) : EtcdConnector(client) {
    fun record(
      context: String,
      t: Throwable,
    ) = recordException(context, t)

    fun reportRecovery(event: WatchRecoveryEvent) = reportRecoveryEvent(event)

    fun reportLease(event: LeaseEvent) = reportLeaseEvent(event)
  }

  /** A mock client whose probe GET either completes or fails, for [EtcdConnector.ping]. */
  private fun clientWithProbe(succeeds: Boolean): Client =
    mockk {
      every { kvClient } returns
        mockk<KV> {
          every { get(any<ByteSequence>(), any<GetOption>()) } answers {
            if (succeeds) {
              // count-only probe: empty kvs, not paginated
              CompletableFuture.completedFuture(
                mockk<GetResponse> {
                  every { kvs } returns emptyList()
                  every { isMore } returns false
                },
              )
            } else {
              // Non-retriable failure → retryRpc rethrows immediately (no slow retry loop)
              CompletableFuture.failedFuture(IllegalStateException("etcd unreachable"))
            }
          }
        }
    }

  init {
    "recordException appends to exceptions and notifies listeners with context" {
      val connector = TestConnector(mockk())
      val seen = CopyOnWriteArrayList<Pair<String, Throwable>>()
      connector.addBackgroundExceptionListener { context, throwable -> seen += context to throwable }

      val boom = RuntimeException("boom")
      connector.record("alpha keep-alive", boom)

      connector.exceptions shouldBe listOf(boom)
      seen shouldBe listOf("alpha keep-alive" to boom)
    }

    "a throwing background-exception listener is caught and does not recurse or re-record" {
      val connector = TestConnector(mockk())
      connector.addBackgroundExceptionListener { _, _ -> throw IllegalStateException("listener blew up") }

      connector.record("ctx", RuntimeException("original"))

      // Exactly the original is recorded — the listener's throw is logged, never re-recorded.
      connector.exceptions.size shouldBe 1
    }

    "removeBackgroundExceptionListener stops delivery" {
      val connector = TestConnector(mockk())
      val count = CopyOnWriteArrayList<Throwable>()
      val listener = BackgroundExceptionListener { _, t -> count += t }
      connector.addBackgroundExceptionListener(listener)
      connector.record("ctx", RuntimeException("1"))
      connector.removeBackgroundExceptionListener(listener)
      connector.record("ctx", RuntimeException("2"))

      count.size shouldBe 1
    }

    "isHealthy is true initially and false after a LOST-inducing event" {
      val connector = TestConnector(mockk())
      connector.isHealthy() shouldBe true

      connector.reportLease(LeaseEvent.Expired(leaseId = 7L, cause = null)) // → ConnectionState.LOST
      connector.isHealthy() shouldBe false
    }

    "isHealthy is false after close" {
      val connector = TestConnector(mockk(relaxed = true))
      connector.isHealthy() shouldBe true
      connector.close()
      connector.isHealthy() shouldBe false
    }

    "ping returns true when the probe GET succeeds" {
      TestConnector(clientWithProbe(succeeds = true)).ping() shouldBe true
    }

    "ping returns false when the probe GET fails" {
      TestConnector(clientWithProbe(succeeds = false)).ping() shouldBe false
    }
  }
}
