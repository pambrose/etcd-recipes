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
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import java.lang.reflect.Proxy

// The connector's state and exception bookkeeping never touch the client
private val unusedClient =
  Proxy.newProxyInstance(Client::class.java.classLoader, arrayOf(Client::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
  } as Client

private val cause = RuntimeException("injected")

/** Exposes the events a recipe's watches and leases report, as its streams would. */
class ReportingConnector : EtcdConnector(unusedClient) {
  fun watch(event: WatchRecoveryEvent) = reportRecoveryEvent(event)

  fun lease(event: LeaseEvent) = reportLeaseEvent(event)

  fun reset() = resetConnectionState()

  fun record(throwable: Throwable) = recordException(throwable)
}

/** The connection-state machine's operations, for Lincheck. */
class ConnectionStateOperations {
  private val connector = ReportingConnector()

  @Operation
  fun watchSuspended() = connector.watch(WatchRecoveryEvent.Suspended("/key", cause))

  @Operation
  fun watchResubscribed() = connector.watch(WatchRecoveryEvent.Resubscribed("/key", 1L))

  @Operation
  fun watchFailed() = connector.watch(WatchRecoveryEvent.Failed("/key", null))

  @Operation
  fun leaseExpired() = connector.lease(LeaseEvent.Expired(1L, null))

  @Operation
  fun leaseRestored() = connector.lease(LeaseEvent.Restored(1L, 2L))

  @Operation
  fun reset() = connector.reset()

  @Operation
  fun state() = connector.connectionState

  @Operation
  fun healthy() = connector.isHealthy()
}

/** The recorded-exceptions list's operations, for Lincheck. */
class RecordedExceptionOperations {
  private val connector = ReportingConnector()

  @Operation
  fun record() = connector.record(cause)

  @Operation
  fun clear() = connector.clearExceptions()

  @Operation
  fun count() = connector.exceptions.size

  @Operation
  fun hasExceptions() = connector.hasExceptions
}

/**
 * An [EtcdConnector]'s connection state and recorded exceptions are updated by every stream a
 * recipe runs (watch dispatchers, lease healers, the acquiring thread), concurrently. Lincheck
 * checks that concurrent updates and reads behave as if they ran one at a time, including a
 * permanent LOST that nothing but a reset clears. (Listener delivery runs on a JDK thread pool,
 * which Lincheck's model checker doesn't handle alongside test threads; ConnectorNotificationTests
 * covers its ordering.)
 */
class EtcdConnectorLincheckTests : StringSpec() {
  private fun modelChecking() = ModelCheckingOptions().iterations(30).invocationsPerIteration(500)

  init {
    "concurrent connection-state events and reads are linearizable" {
      modelChecking().check(ConnectionStateOperations::class)
    }

    "concurrent exception recording, clearing, and reads are linearizable" {
      modelChecking().check(RecordedExceptionOperations::class)
    }
  }
}
