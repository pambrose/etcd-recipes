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

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.lease.LeaseRevokeResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.recipes.common.pollUntil
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class TransientKeyValueKeepAliveErrorTests : StringSpec() {
    init {
        // Regression test: once the key is established, a keep-alive stream death
        // must land on the recipe's exceptions list so a caller can see the key is
        // no longer being renewed, instead of it silently expiring while the worker
        // still blocks on its wait latch.
        "records a keep-alive stream failure to exceptions" {
            val leaseId = 11L
            val observerSlot = slot<StreamObserver<LeaseKeepAliveResponse>>()

            val leaseMock = mockk<Lease>()
            every { leaseMock.grant(any()) } returns
                CompletableFuture.completedFuture(mockk<LeaseGrantResponse> { every { id } returns leaseId })
            every {
                leaseMock.keepAlive(eq(leaseId), capture(observerSlot))
            } returns mockk<CloseableClient>(relaxed = true)
            every { leaseMock.revoke(any()) } returns CompletableFuture.completedFuture(mockk<LeaseRevokeResponse>())

            val kvMock = mockk<KV>()
            every { kvMock.put(any(), any(), any()) } returns CompletableFuture.completedFuture(mockk<PutResponse>())

            val client = mockk<Client>()
            every { client.leaseClient } returns leaseMock
            every { client.kvClient } returns kvMock

            // autoStart=true: the constructor starts the worker, which establishes
            // the key and then blocks on the wait latch. start() returns once the
            // keep-alive observer is wired, so observerSlot is captured by here.
            val tkv = TransientKeyValue(client, "/transient/key", "value", leaseTtlSecs = 5L)

            val boom = RuntimeException("keep-alive stream died")
            observerSlot.captured.onError(boom)

            // Lease events are dispatched on the healer thread (off jetcd's callback
            // thread), so the recording is asynchronous.
            pollUntil(10.seconds) { tkv.hasExceptions } shouldBe true
            tkv.exceptions shouldContain boom

            tkv.close()
        }
    }
}
