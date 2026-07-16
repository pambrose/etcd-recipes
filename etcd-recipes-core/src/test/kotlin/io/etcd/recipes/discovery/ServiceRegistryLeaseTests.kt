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

import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.FailingLeaseMocks
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.verify

class ServiceRegistryLeaseTests : StringSpec() {
    init {
        // Regression test: when the CAS that publishes the instance key fails,
        // registerService() must revoke the lease it just granted instead of
        // letting it linger in etcd until its TTL expires.
        "revokes the granted lease when the registration CAS fails" {
            val mocks = FailingLeaseMocks(leaseId = 77L)
            val registry = ServiceRegistry(mocks.client, "/discovery")
            val service = serviceInstance("test-service", "payload")

            shouldThrow<EtcdRecipeException> { registry.registerService(service) }

            verify(exactly = 1) { mocks.lease.revoke(mocks.leaseId) }
        }

        // Regression test: a failed registration must not leave a half-built context
        // (lease set, keepAlive unset) in serviceContextMap. Before the fix close()
        // iterated it and threw IllegalStateException reading the unset keepAlive
        // delegate, aborting cleanup of the whole registry.
        "leaves the context map clean so close() is safe after a failed registration" {
            val mocks = FailingLeaseMocks(leaseId = 77L)
            val registry = ServiceRegistry(mocks.client, "/discovery")
            val service = serviceInstance("test-service", "payload")

            shouldThrow<EtcdRecipeException> { registry.registerService(service) }

            shouldNotThrowAny { registry.close() }
        }
    }
}
