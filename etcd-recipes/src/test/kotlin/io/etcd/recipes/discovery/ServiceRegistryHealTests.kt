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

package io.etcd.recipes.discovery

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Txn
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.pollUntil
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [ServiceRegistry] through a lease expiry with a mocked jetcd [Client]:
 * the instance key must be re-registered (CAS re-put) under a freshly granted
 * lease, so discovery recovers automatically after a partition longer than the
 * TTL.
 */
class ServiceRegistryHealTests : StringSpec() {
  private class RegistryHealMocks {
    val observers = CopyOnWriteArrayList<StreamObserver<LeaseKeepAliveResponse>>()
    val txnCount = AtomicInteger(0)
    private val nextId = AtomicLong(100)

    val client: Client =
      mockk {
        every { leaseClient } returns
          mockk<Lease> {
            every { grant(any()) } answers {
              CompletableFuture.completedFuture(
                mockk<LeaseGrantResponse> { every { id } returns nextId.getAndIncrement() },
              )
            }
            every { grant(any(), any(), any()) } answers {
              CompletableFuture.completedFuture(
                mockk<LeaseGrantResponse> { every { id } returns nextId.getAndIncrement() },
              )
            }
            every { keepAlive(any(), any()) } answers {
              observers += secondArg<StreamObserver<LeaseKeepAliveResponse>>()
              mockk<CloseableClient>(relaxed = true)
            }
            every { revoke(any()) } returns CompletableFuture.completedFuture(mockk())
          }
        every { kvClient } returns
          mockk<KV> {
            every { txn() } answers {
              txnCount.incrementAndGet()
              mockk<Txn> {
                every { If(*anyVararg()) } returns this
                every { Then(*anyVararg()) } returns this
                every { commit() } returns
                  CompletableFuture.completedFuture(mockk<TxnResponse> { every { isSucceeded } returns true })
              }
            }
            every { delete(any()) } returns CompletableFuture.completedFuture(mockk<DeleteResponse>())
          }
      }
  }

  init {
    "expired instance lease is re-granted and the instance re-registered" {
      val mocks = RegistryHealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()
      val service = ServiceInstance("svc", "payload")

      ServiceRegistry(mocks.client, "/registry/heal").use { registry ->
        registry.addLeaseListener { events += it }
        registry.registerService(service)
        mocks.txnCount.get() shouldBe 1 // initial CAS registration

        // jetcd's DeadLine service fires onCompleted when the lease expires unrenewed
        mocks.observers.first().onCompleted()

        pollUntil(10.seconds) { events.any { it is LeaseEvent.Restored } } shouldBe true
        mocks.txnCount.get() shouldBe 2 // re-registration CAS under the healed lease
      }
    }
  }
}
