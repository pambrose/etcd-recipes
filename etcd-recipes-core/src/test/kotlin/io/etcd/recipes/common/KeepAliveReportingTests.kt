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
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [keepAlive]'s `onKeepAliveError` means "renewal stopped: the lease will expire". jetcd
 * restarts the keep-alive stream itself after a transient error, with renewal continuing,
 * so only a completed stream or etcd's NOT_FOUND "requested lease not found" should fire
 * it; a transient error used to fire it too, so callers tore down on a harmless blip.
 */
class KeepAliveReportingTests : StringSpec() {
  init {
    "keepAlive reports a lost lease, not a transient stream error jetcd recovers from" {
      val observers = CopyOnWriteArrayList<StreamObserver<LeaseKeepAliveResponse>>()
      val lease =
        mockk<Lease> {
          every { keepAlive(any(), any()) } answers {
            observers += secondArg<StreamObserver<LeaseKeepAliveResponse>>()
            mockk<CloseableClient>(relaxed = true)
          }
        }
      val client = mockk<Client> { every { leaseClient } returns lease }
      val stopped = CopyOnWriteArrayList<Throwable>()

      client.keepAlive(mockk<LeaseGrantResponse> { every { id } returns 7L }) { stopped += it }
      val stream = observers.single()

      stream.onError(StatusRuntimeException(Status.UNAVAILABLE.withDescription("connection reset")))
      stopped.shouldBeEmpty()

      stream.onError(
        EtcdExceptionFactory.newEtcdException(ErrorCode.NOT_FOUND, "etcdserver: requested lease not found"),
      )
      stopped.size shouldBe 1

      stream.onCompleted()
      stopped.size shouldBe 2
    }
  }
}
