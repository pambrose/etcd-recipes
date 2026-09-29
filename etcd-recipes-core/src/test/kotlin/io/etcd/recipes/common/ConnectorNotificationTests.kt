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
import io.etcd.recipes.coroutines.backgroundExceptionsAsFlow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * The connector's notification and state model:
 *
 * - listeners run on the connector's own notifier thread, never on the reporting thread
 *   (which can be jetcd's event loop), and in the order the state changed;
 * - a LOST from an abandoned stream (`Failed`) sticks until the recipe restarts, so a
 *   later event from a healthy stream can't mask it; a lock loss (`Expired`) doesn't;
 * - the exception list keeps the most recent 100, counting the rest as dropped;
 * - a slow `backgroundExceptionsAsFlow` collector never blocks recording.
 */
class ConnectorNotificationTests : StringSpec() {
  private class Probe(
    client: Client = mockk(relaxed = true),
  ) : EtcdConnector(client) {
    fun record(e: Throwable) = recordException(e)

    fun lease(event: LeaseEvent) = reportLeaseEvent(event)

    fun recovery(event: WatchRecoveryEvent) = reportRecoveryEvent(event)

    fun reset() = resetConnectionState()
  }

  init {
    "listeners run on the connector's notifier, not on the reporting thread" {
      Probe().use { probe ->
        val exceptionThread = AtomicReference<String?>(null)
        val stateThread = AtomicReference<String?>(null)
        probe.addBackgroundExceptionListener { _, _ -> exceptionThread.store(Thread.currentThread().name) }
        probe.addConnectionStateListener { _, _ -> stateThread.store(Thread.currentThread().name) }
        thread(name = "jetcd-callback") {
          probe.record(RuntimeException("boom"))
          probe.lease(LeaseEvent.Suspended(1L, RuntimeException("blip")))
        }.join()
        pollUntil(5.seconds) { exceptionThread.load() != null && stateThread.load() != null } shouldBe true
        exceptionThread.load()!! shouldStartWith "etcd-recipe-notifier"
        stateThread.load()!! shouldStartWith "etcd-recipe-notifier"
      }
    }

    "a listener that blocks doesn't block the reporting thread" {
      Probe().use { probe ->
        val release = CountDownLatch(1)
        probe.addBackgroundExceptionListener { _, _ -> release.await() }
        val reported = CountDownLatch(1)
        thread(isDaemon = true) {
          probe.record(RuntimeException("first"))
          probe.record(RuntimeException("second"))
          reported.countDown()
        }
        withClue("recordException waited on a listener") { reported.await(5, TimeUnit.SECONDS) shouldBe true }
        release.countDown()
      }
    }

    "a LOST from an abandoned stream sticks until the recipe restarts" {
      Probe().use { probe ->
        probe.lease(LeaseEvent.Failed(1L, null))
        probe.recovery(WatchRecoveryEvent.Resubscribed("/other-stream", 5L))
        withClue("a healthy stream masked a dead one") { probe.connectionState shouldBe ConnectionState.LOST }
        probe.isHealthy() shouldBe false

        probe.reset()
        probe.connectionState shouldBe ConnectionState.CONNECTED
        probe.recovery(WatchRecoveryEvent.Failed("/k", null))
        probe.lease(LeaseEvent.Restored(2L, 3L))
        probe.connectionState shouldBe ConnectionState.LOST
      }
    }

    "a lock loss (Expired) is not sticky" {
      Probe().use { probe ->
        probe.lease(LeaseEvent.Expired(1L, null))
        probe.connectionState shouldBe ConnectionState.LOST
        probe.lease(LeaseEvent.Restored(1L, 2L))
        probe.connectionState shouldBe ConnectionState.RECONNECTED
      }
    }

    "listeners see state changes in the order they happened" {
      Probe().use { probe ->
        val lastSeen = AtomicReference<ConnectionState?>(null)
        probe.addConnectionStateListener { newState, _ -> lastSeen.store(newState) }
        val reporters =
          List(2) { n ->
            thread {
              repeat(500) { i ->
                if ((i + n) % 2 ==
                  0
                )
                  probe.lease(LeaseEvent.Suspended(1L, RuntimeException("blip")))
                  else
                  probe.lease(LeaseEvent.Restored(1L, 1L))
              }
            }
          }
        reporters.forEach { it.join() }
        withClue("the last notification disagrees with the final state") {
          pollUntil(5.seconds) { lastSeen.load() == probe.connectionState } shouldBe true
        }
      }
    }

    "the exception list keeps the most recent 100 and counts the rest" {
      Probe().use { probe ->
        repeat(150) { probe.record(RuntimeException("e$it")) }
        probe.exceptions.size shouldBe 100
        probe.exceptions.first().message shouldBe "e50"
        probe.droppedExceptionCount shouldBe 50L
      }
    }

    "a slow backgroundExceptionsAsFlow collector never blocks recording" {
      Probe().use { probe ->
        val scope = CoroutineScope(Dispatchers.Default)
        try {
          val collecting = CountDownLatch(1)
          scope.launch {
            probe.backgroundExceptionsAsFlow(capacity = 1).collect {
              collecting.countDown()
              awaitCancellation()
            }
          }
          // Record from a daemon until the collector has one (the flow subscribes asynchronously)
          thread(isDaemon = true) {
            while (collecting.count > 0L) {
              probe.record(RuntimeException("first"))
              Thread.sleep(50)
            }
          }
          pollUntil(5.seconds) { collecting.count == 0L } shouldBe true
          val recorded = CountDownLatch(1)
          thread(isDaemon = true) {
            repeat(10) { probe.record(RuntimeException("more")) }
            recorded.countDown()
          }
          withClue("recording waited on a slow collector") { recorded.await(5, TimeUnit.SECONDS) shouldBe true }
          val stateSeen = CountDownLatch(1)
          probe.addConnectionStateListener { _, _ -> stateSeen.countDown() }
          probe.lease(LeaseEvent.Suspended(1L, RuntimeException("blip")))
          withClue("a slow collector stalled the notifier") { stateSeen.await(5, TimeUnit.SECONDS) shouldBe true }
        } finally {
          scope.cancel()
        }
      }
    }
  }
}
