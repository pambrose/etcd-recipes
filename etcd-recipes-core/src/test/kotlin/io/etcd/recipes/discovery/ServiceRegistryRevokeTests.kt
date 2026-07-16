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
import io.etcd.jetcd.lease.LeaseRevokeResponse
import io.etcd.jetcd.support.CloseableClient
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture

class ServiceRegistryRevokeTests : StringSpec() {
    init {
        // Regression test for #7: a successful registration revokes its lease exactly
        // once — on normal unregister/close, not on the (successful) registration —
        // so the lease and its bound key are released promptly instead of at TTL.
        "revokes the lease exactly once on normal close" {
            val leaseId = 21L
            val lease = mockk<Lease>()
            every { lease.grant(any()) } returns
                CompletableFuture.completedFuture(mockk<LeaseGrantResponse> { every { id } returns leaseId })
            every { lease.keepAlive(eq(leaseId), any()) } returns mockk<CloseableClient>(relaxed = true)
            every { lease.revoke(leaseId) } returns CompletableFuture.completedFuture(mockk<LeaseRevokeResponse>())

            val txn = mockk<Txn>()
            every { txn.If(*anyVararg()) } returns txn
            every { txn.Then(*anyVararg()) } returns txn
            every { txn.commit() } returns
                CompletableFuture.completedFuture(mockk<TxnResponse> { every { isSucceeded } returns true })

            val kv = mockk<KV>()
            every { kv.txn() } returns txn
            every { kv.delete(any()) } returns CompletableFuture.completedFuture(mockk<DeleteResponse>())

            val client = mockk<Client>()
            every { client.kvClient } returns kv
            every { client.leaseClient } returns lease

            val registry = ServiceRegistry(client, "/discovery")
            registry.registerService(serviceInstance("test-service", "payload"))

            // Registration succeeded, so nothing was revoked yet.
            verify(exactly = 0) { lease.revoke(leaseId) }

            registry.close()

            verify(exactly = 1) { lease.revoke(leaseId) }
        }
    }
}
