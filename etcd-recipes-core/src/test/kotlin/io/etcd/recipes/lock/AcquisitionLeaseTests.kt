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

import io.etcd.jetcd.Client
import io.etcd.jetcd.Lease
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.recipes.common.RpcResilience
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An acquisition lease reports a transient keep-alive error with its real lease id, and
 * reports the stream's recovery on the first renewal after one, so a recipe's
 * `connectionState` doesn't stay SUSPENDED after jetcd quietly restarts the stream.
 */
class AcquisitionLeaseTests : StringSpec() {
  init {
    "a transient error is reported with the real lease id, and the next renewal as resumed" {
      val observers = CopyOnWriteArrayList<StreamObserver<LeaseKeepAliveResponse>>()
      val lease =
        mockk<Lease> {
          every { grant(any()) } returns
            CompletableFuture.completedFuture(mockk<LeaseGrantResponse> { every { id } returns 42L })
          every { keepAlive(any(), any()) } answers {
            observers += secondArg<StreamObserver<LeaseKeepAliveResponse>>()
            mockk<CloseableClient>(relaxed = true)
          }
          every { revoke(any()) } returns CompletableFuture.completedFuture(mockk())
        }
      val client = mockk<Client> { every { leaseClient } returns lease }
      val transients = CopyOnWriteArrayList<Long>()
      val resumed = CopyOnWriteArrayList<Long>()
      AcquisitionLease(
        client,
        2L,
        RpcResilience.DEFAULT,
        onTransient = { leaseId, _ -> transients += leaseId },
        onResumed = { leaseId -> resumed += leaseId },
        onFatal = { },
      ).use {
        val observer = observers.single()
        observer.onNext(mockk(relaxed = true))
        resumed shouldBe emptyList()
        observer.onError(RuntimeException("stream reset"))
        transients shouldBe listOf(42L)
        observer.onNext(mockk(relaxed = true))
        observer.onNext(mockk(relaxed = true))
        resumed shouldBe listOf(42L)
      }
    }
  }
}
