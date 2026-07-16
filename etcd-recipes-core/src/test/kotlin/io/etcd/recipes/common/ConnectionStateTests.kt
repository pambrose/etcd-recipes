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

import io.etcd.jetcd.Client
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for the connection-state machinery on [EtcdConnector]: recipes feed
 * their watch-recovery and lease events into the protected report hooks, and the
 * connector derives CONNECTED/SUSPENDED/RECONNECTED/LOST transitions for listeners.
 * No etcd needed — the client is never touched.
 */
class ConnectionStateTests : StringSpec() {
  private class TestConnector : EtcdConnector(mockk<Client>()) {
    fun watchEvent(event: WatchRecoveryEvent) = reportRecoveryEvent(event)

    fun leaseEvent(event: LeaseEvent) = reportLeaseEvent(event)
  }

  private val boom = RuntimeException("stream error")

  init {
    "initial state is CONNECTED" {
      TestConnector().connectionState shouldBe ConnectionState.CONNECTED
    }

    "watch suspension and recovery drive SUSPENDED then RECONNECTED" {
      val connector = TestConnector()
      val transitions = CopyOnWriteArrayList<Pair<ConnectionState, ConnectionState>>()
      connector.addConnectionStateListener { new, prev -> transitions += new to prev }

      connector.watchEvent(WatchRecoveryEvent.Suspended("/k", boom))
      connector.connectionState shouldBe ConnectionState.SUSPENDED

      connector.watchEvent(WatchRecoveryEvent.Resubscribed("/k", 42))
      connector.connectionState shouldBe ConnectionState.RECONNECTED

      transitions shouldBe listOf(
        ConnectionState.SUSPENDED to ConnectionState.CONNECTED,
        ConnectionState.RECONNECTED to ConnectionState.SUSPENDED,
      )
    }

    "lease expiry drives LOST and restoration drives RECONNECTED" {
      val connector = TestConnector()
      connector.leaseEvent(LeaseEvent.Expired(7L, null))
      connector.connectionState shouldBe ConnectionState.LOST

      connector.leaseEvent(LeaseEvent.Restored(7L, 8L))
      connector.connectionState shouldBe ConnectionState.RECONNECTED
    }

    "abandoned recovery drives LOST" {
      val connector = TestConnector()
      connector.watchEvent(WatchRecoveryEvent.Failed("/k", boom))
      connector.connectionState shouldBe ConnectionState.LOST
    }

    "repeated events do not re-notify" {
      val connector = TestConnector()
      val transitions = CopyOnWriteArrayList<ConnectionState>()
      connector.addConnectionStateListener { new, _ -> transitions += new }

      connector.watchEvent(WatchRecoveryEvent.Suspended("/k", boom))
      connector.leaseEvent(LeaseEvent.Suspended(7L, boom))
      connector.watchEvent(WatchRecoveryEvent.Suspended("/k2", boom))

      transitions shouldBe listOf(ConnectionState.SUSPENDED)
    }

    "listener exceptions are recorded and do not block other listeners" {
      val connector = TestConnector()
      val seen = CopyOnWriteArrayList<ConnectionState>()
      connector.addConnectionStateListener { _, _ -> throw IllegalStateException("listener bug") }
      connector.addConnectionStateListener { new, _ -> seen += new }

      connector.watchEvent(WatchRecoveryEvent.Suspended("/k", boom))

      seen shouldBe listOf(ConnectionState.SUSPENDED)
      connector.hasExceptions shouldBe true
      connector.exceptions.first().shouldBeInstanceOf<IllegalStateException>()
    }

    "removed listeners stop receiving transitions" {
      val connector = TestConnector()
      val seen = CopyOnWriteArrayList<ConnectionState>()
      val listener = ConnectionStateListener { new, _ -> seen += new }
      connector.addConnectionStateListener(listener)
      connector.watchEvent(WatchRecoveryEvent.Suspended("/k", boom))
      connector.removeConnectionStateListener(listener)
      connector.watchEvent(WatchRecoveryEvent.Resubscribed("/k", 1))

      seen shouldBe listOf(ConnectionState.SUSPENDED)
    }
  }
}
