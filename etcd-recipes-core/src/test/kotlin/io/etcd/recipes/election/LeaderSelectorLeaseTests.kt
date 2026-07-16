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

package io.etcd.recipes.election

import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.FailingLeaseMocks
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.Executor

class LeaderSelectorLeaseTests : StringSpec() {
    init {
        // Regression test: when the CAS that registers participation fails,
        // advertiseParticipation() must revoke the lease it just granted instead
        // of letting it linger in etcd until its TTL expires.
        //
        // A failing transaction also makes isKeyPresent() report absent, so the
        // pre-CAS wait loop exits immediately rather than sleeping.
        "revokes the granted lease when the participation CAS fails" {
            val mocks = FailingLeaseMocks(leaseId = 99L)

            val selector =
                LeaderSelector(
                    mocks.client,
                    "/election",
                    mockk<LeaderSelectorListener>(relaxed = true),
                    leaseTtlSecs = 1L,
                    // A direct executor avoids spinning up a real thread pool.
                    userExecutor = Executor { it.run() },
                )

            shouldThrow<EtcdRecipeException> { selector.advertiseParticipation() }

            verify(exactly = 1) { mocks.lease.revoke(mocks.leaseId) }
        }
    }
}
