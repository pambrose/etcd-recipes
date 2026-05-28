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
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Txn
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseRevokeResponse
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture

/**
 * MockK fixture for a [Client] whose every transaction reports failure and whose
 * lease grant returns [leaseId]. Drives a recipe's "lease granted, then CAS fails"
 * path so a test can assert the lease is revoked. Expose [lease] so the caller can
 * `verify { lease.revoke(leaseId) }`.
 */
internal class FailingLeaseMocks(
  val leaseId: Long,
) {
    val lease = mockk<Lease>()
    val client: Client

    init {
        val txn = mockk<Txn>()
        val failedTxn = mockk<TxnResponse> { every { isSucceeded } returns false }
        every { txn.If(*anyVararg()) } returns txn
        every { txn.Then(*anyVararg()) } returns txn
        every { txn.commit() } returns CompletableFuture.completedFuture(failedTxn)

        val kv = mockk<KV> { every { txn() } returns txn }

        val granted = mockk<LeaseGrantResponse> { every { id } returns leaseId }
        every { lease.grant(any()) } returns CompletableFuture.completedFuture(granted)
        every { lease.revoke(leaseId) } returns CompletableFuture.completedFuture(mockk<LeaseRevokeResponse>())

        client =
            mockk {
                every { kvClient } returns kv
                every { leaseClient } returns lease
            }
    }
}
