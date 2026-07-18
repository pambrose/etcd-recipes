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

package io.etcd.recipes.keyvalue

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.options.PutOption
import io.etcd.jetcd.support.CloseableClient
import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.pollUntil
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [TransientKeyValue] through a lease expiry with a mocked jetcd [Client]:
 * the key must be re-put under a freshly granted lease (self-healing), the lease
 * events must be observable, and the connector's connection state must go
 * LOST -> RECONNECTED. jetcd reports expiry via the keep-alive observer's
 * onCompleted, which is what the test drives.
 */
class TransientKeyValueHealTests : StringSpec() {
  private class KvHealMocks {
    val observers = CopyOnWriteArrayList<StreamObserver<LeaseKeepAliveResponse>>()
    val putLeaseIds = CopyOnWriteArrayList<Long>()
    private val nextId = AtomicLong(100)

    val client: Client =
      mockk {
        every { leaseClient } returns
          mockk<Lease> {
            every { grant(any()) } answers {
              CompletableFuture.completedFuture(
                mockk<LeaseGrantResponse> {
                  every { id } returns nextId.fetchAndIncrement()
                },
              )
            }
            every { grant(any(), any(), any()) } answers {
              CompletableFuture.completedFuture(
                mockk<LeaseGrantResponse> {
                  every { id } returns nextId.fetchAndIncrement()
                },
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
            every { put(any<ByteSequence>(), any<ByteSequence>(), any<PutOption>()) } answers {
              putLeaseIds += thirdArg<PutOption>().leaseId
              CompletableFuture.completedFuture(mockk<PutResponse>())
            }
          }
      }
  }

  init {
    "expired lease is re-granted and the key re-put under the new lease" {
      val mocks = KvHealMocks()
      val events = CopyOnWriteArrayList<LeaseEvent>()

      TransientKeyValue(mocks.client, "/tkv/heal", "value", autoStart = false).use { tkv ->
        tkv.addLeaseListener { events += it }
        tkv.start()

        mocks.putLeaseIds shouldBe [100L]

        // jetcd's DeadLine service fires onCompleted when the lease expires unrenewed
        mocks.observers.first().onCompleted()

        pollUntil(10.seconds) { events.any { it is LeaseEvent.Restored } } shouldBe true
        mocks.putLeaseIds shouldBe [100L, 101L]
        tkv.connectionState shouldBe ConnectionState.RECONNECTED
        tkv.hasExceptions shouldBe true // the expiry itself is still recorded
      }
    }
  }
}
