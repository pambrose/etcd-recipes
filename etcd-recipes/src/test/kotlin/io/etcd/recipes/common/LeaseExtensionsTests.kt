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
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.support.CloseableClient
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class LeaseExtensionsTests : StringSpec() {
    // A Client whose leaseClient.keepAlive captures the StreamObserver jetcd would
    // normally drive, so a test can fire onError/onCompleted by hand and assert that
    // keepAlive's onKeepAliveError callback is invoked.
    private fun capturingClient(
      leaseId: Long,
      observerSlot: CapturingSlot<StreamObserver<LeaseKeepAliveResponse>>,
    ): Client {
        val leaseMock = mockk<Lease>()
        every {
            leaseMock.keepAlive(eq(leaseId), capture(observerSlot))
        } returns mockk<CloseableClient>(relaxed = true)
        return mockk { every { leaseClient } returns leaseMock }
    }

    private fun leaseOf(leaseId: Long): LeaseGrantResponse = mockk { every { id } returns leaseId }

    init {
        "keepAlive surfaces a stream error through onKeepAliveError" {
            val observerSlot = slot<StreamObserver<LeaseKeepAliveResponse>>()
            val client = capturingClient(leaseId = 5L, observerSlot = observerSlot)

            val recorded = mutableListOf<Throwable>()
            client.keepAlive(leaseOf(5L)) { recorded += it }

            val boom = RuntimeException("stream broke")
            observerSlot.captured.onError(boom)

            recorded shouldContainExactly listOf(boom)
        }

        // onCompleted carries no throwable, so keepAlive synthesizes an
        // EtcdRecipeRuntimeException — an unexpected completion means renewal
        // stopped (it never fires on our own CloseableClient.close()).
        "keepAlive surfaces an unexpected completion through onKeepAliveError" {
            val observerSlot = slot<StreamObserver<LeaseKeepAliveResponse>>()
            val client = capturingClient(leaseId = 6L, observerSlot = observerSlot)

            val recorded = mutableListOf<Throwable>()
            client.keepAlive(leaseOf(6L)) { recorded += it }

            observerSlot.captured.onCompleted()

            recorded.size shouldBe 1
            recorded.first().shouldBeInstanceOf<EtcdRecipeRuntimeException>()
        }

        // The default no-op callback must not blow up — guards backward compat for
        // existing callers that pass no callback.
        "keepAlive with the default callback ignores stream errors" {
            val observerSlot = slot<StreamObserver<LeaseKeepAliveResponse>>()
            val client = capturingClient(leaseId = 7L, observerSlot = observerSlot)

            client.keepAlive(leaseOf(7L))

            observerSlot.captured.onError(RuntimeException("ignored"))
            observerSlot.captured.onCompleted()
        }
    }
}
