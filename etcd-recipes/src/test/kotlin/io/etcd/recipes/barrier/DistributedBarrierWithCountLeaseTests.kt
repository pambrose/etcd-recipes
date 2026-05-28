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

package io.etcd.recipes.barrier

import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.FailingLeaseMocks
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.verify

class DistributedBarrierWithCountLeaseTests : StringSpec() {
    init {
        // Regression test: when the CAS that claims the waiting-path key fails,
        // waitOnBarrier() must revoke the lease it just granted instead of
        // letting it linger in etcd until its TTL expires.
        "revokes the granted lease when the waiting-path CAS fails" {
            val mocks = FailingLeaseMocks(leaseId = 42L)

            DistributedBarrierWithCount(mocks.client, "/barrier", memberCount = 1).use { barrier ->
                shouldThrow<EtcdRecipeException> { barrier.waitOnBarrier() }
            }

            verify(exactly = 1) { mocks.lease.revoke(mocks.leaseId) }
        }
    }
}
