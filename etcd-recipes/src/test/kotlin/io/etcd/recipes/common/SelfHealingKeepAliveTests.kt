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
import io.etcd.jetcd.Lease
import io.etcd.jetcd.common.exception.ErrorCode
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.support.CloseableClient
import io.grpc.stub.StreamObserver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [SelfHealingKeepAlive]. jetcd's [Lease] is mocked (in the style of
 * [FailingLeaseMocks]) so tests can drive the captured keep-alive [StreamObserver]:
 * jetcd reports lease expiry via `onCompleted` (deadline passed unrenewed) or
 * `onError(NOT_FOUND "requested lease not found")`, and transient stream failures
 * via other `onError`s — exactly what these tests simulate. No etcd needed.
 */
class SelfHealingKeepAliveTests : StringSpec() {
  private class HealMocks {
    val observers = CopyOnWriteArrayList<StreamObserver<LeaseKeepAliveResponse>>()
    val registrations = CopyOnWriteArrayList<CloseableClient>()
    private val nextId = AtomicLong(100)

    /** Heal-path grant calls that should fail before one succeeds. */
    val grantFailures = AtomicInteger(0)

    private fun granted(): CompletableFuture<LeaseGrantResponse> {
      val id = nextId.getAndIncrement()
      return CompletableFuture.completedFuture(mockk<LeaseGrantResponse> { every { this@mockk.id } returns id })
    }

    val lease: Lease =
      mockk {
        every { grant(any()) } answers { granted() }
        every { grant(any(), any(), any()) } answers {
          if (grantFailures.getAndDecrement() > 0) {
            CompletableFuture.failedFuture(IllegalStateException("grant refused"))
          } else {
            granted()
          }
        }
        every { keepAlive(any(), any()) } answers {
          observers += secondArg<StreamObserver<LeaseKeepAliveResponse>>()
          mockk<CloseableClient>(relaxed = true).also { registrations += it }
        }
        every { revoke(any()) } returns CompletableFuture.completedFuture(mockk())
      }

    val client: Client = mockk { every { leaseClient } returns lease }
  }

  private fun quickHeals() = LeaseResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds))

  private fun leaseNotFound() =
    EtcdExceptionFactory.newEtcdException(ErrorCode.NOT_FOUND, "etcdserver: requested lease not found")

  init {
    "re-grants and re-establishes after keep-alive completion" {
      val mocks = HealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()
      val establishedIds = CopyOnWriteArrayList<Long>()
      mocks.client.selfHealingKeepAlive(2.seconds, quickHeals(), { events += it }) { lease ->
        establishedIds += lease.id
        true
      }.use { healer ->
        healer.currentLeaseId shouldBe 100L
        healer.isHealthy shouldBe true

        // jetcd's DeadLine service fires onCompleted when the lease outlives its TTL unrenewed
        mocks.observers.first().onCompleted()

        pollUntil(10.seconds) { events.any { it is LeaseEvent.Restored } } shouldBe true
        establishedIds shouldBe listOf(100L, 101L)
        healer.currentLeaseId shouldBe 101L
        healer.isHealthy shouldBe true
        mocks.registrations.size shouldBe 2

        val expired = events.filterIsInstance<LeaseEvent.Expired>().first()
        expired.leaseId shouldBe 100L
        val restored = events.filterIsInstance<LeaseEvent.Restored>().first()
        restored.oldLeaseId shouldBe 100L
        restored.newLeaseId shouldBe 101L
      }
    }

    "keep-alive lease transitions increment the keep-alive metric" {
      val mocks = HealMocks()
      val kinds = CopyOnWriteArrayList<String>()
      val metrics =
        object : EtcdMetrics {
          override fun incrementKeepAlive(
            kind: String,
            leaseId: Long,
          ) {
            kinds += kind
          }
        }
      mocks.client.selfHealingKeepAlive(2.seconds, quickHeals().withMetrics(metrics), null) { true }
        .use {
          mocks.observers.first().onCompleted() // expiry → heal → restore
          pollUntil(10.seconds) { kinds.contains("restored") } shouldBe true
          kinds shouldContain "expired"
        }
    }

    "NOT_FOUND stream error heals like an expiry" {
      val mocks = HealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()

      mocks.client.selfHealingKeepAlive(2.seconds, quickHeals(), { events += it }) { true }
        .use { healer ->
          mocks.observers.first().onError(leaseNotFound())
          pollUntil(10.seconds) { events.any { it is LeaseEvent.Restored } } shouldBe true
          healer.currentLeaseId shouldBe 101L
        }
    }

    "transient stream errors report Suspended without healing" {
      val mocks = HealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()

      mocks.client.selfHealingKeepAlive(2.seconds, quickHeals(), { events += it }) { true }
        .use { healer ->
          mocks.observers.first().onError(IllegalStateException("connection reset"))
          pollUntil(5.seconds) { events.any { it is LeaseEvent.Suspended } } shouldBe true
          Thread.sleep(100)
          events.none { it is LeaseEvent.Expired } shouldBe true
          healer.currentLeaseId shouldBe 100L // no re-grant
          mocks.registrations.size shouldBe 1
        }
    }

    "establish returning false on heal emits Failed and stops" {
      val mocks = HealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()
      val calls = AtomicInteger(0)

      mocks.client.selfHealingKeepAlive(2.seconds, quickHeals(), { events += it }) {
        calls.incrementAndGet() == 1 // initial establish succeeds; heals decline
      }.use { healer ->
        mocks.observers.first().onCompleted()
        pollUntil(10.seconds) { events.any { it is LeaseEvent.Failed } } shouldBe true
        Thread.sleep(100)
        events.none { it is LeaseEvent.Restored } shouldBe true
        healer.isHealthy shouldBe false
      }
    }

    "heal attempts retry grant failures under the policy" {
      val mocks = HealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()
      mocks.grantFailures.set(2)

      mocks.client.selfHealingKeepAlive(2.seconds, quickHeals(), { events += it }) { true }
        .use { healer ->
          mocks.observers.first().onCompleted()
          pollUntil(10.seconds) { events.any { it is LeaseEvent.Restored } } shouldBe true
          healer.currentLeaseId shouldBe 101L
        }
    }

    "policy exhaustion emits Failed" {
      val mocks = HealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()
      mocks.grantFailures.set(Int.MAX_VALUE)
      val resilience = LeaseResilience(RetryPolicy.bounded(maxAttempts = 2, delay = 10.milliseconds))

      mocks.client.selfHealingKeepAlive(2.seconds, resilience, { events += it }) { true }
        .use {
          mocks.observers.first().onCompleted()
          pollUntil(10.seconds) { events.any { it is LeaseEvent.Failed } } shouldBe true
          events.none { it is LeaseEvent.Restored } shouldBe true
        }
    }

    "close during healing cancels further attempts" {
      val mocks = HealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()
      mocks.grantFailures.set(Int.MAX_VALUE)
      val resilience = LeaseResilience(RetryPolicy.bounded(maxAttempts = 1000, delay = 20.milliseconds))

      val healer = mocks.client.selfHealingKeepAlive(2.seconds, resilience, { events += it }) { true }
      mocks.observers.first().onCompleted()
      pollUntil(5.seconds) { events.any { it is LeaseEvent.Expired } } shouldBe true
      healer.close()

      Thread.sleep(200)
      val grantsAtClose = mocks.grantFailures.get()
      Thread.sleep(300)
      // grantFailures decrements once per heal-grant attempt; no movement = no attempts
      (mocks.grantFailures.get() >= grantsAtClose - 1) shouldBe true
      events.none { it is LeaseEvent.Restored } shouldBe true
    }

    "initial establish returning false aborts and revokes the lease" {
      val mocks = HealMocks()

      shouldThrow<EtcdRecipeRuntimeException> {
        mocks.client.selfHealingKeepAlive(2.seconds, quickHeals(), null) { false }
      }
      verify { mocks.lease.revoke(100L) }
    }

    "close revokes the current lease best-effort" {
      val mocks = HealMocks()
      mocks.client.selfHealingKeepAlive(2.seconds, quickHeals(), null) { true }.close()
      verify { mocks.lease.revoke(100L) }
      verify { mocks.registrations.first().close() }
    }
  }
}
