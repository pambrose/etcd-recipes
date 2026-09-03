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
import io.etcd.jetcd.Lease
import io.etcd.jetcd.common.exception.ErrorCode
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseRevokeResponse
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.FailingLeaseMocks
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A failed [ServiceRegistry.registerService] has two very different causes — the
 * instance key already exists (the CAS lost) or etcd could not be reached at all —
 * and the caller has to be able to tell them apart. These tests pin the reporting:
 * the thrown [EtcdRecipeException] must carry the underlying failure as its cause,
 * and the registration must respect the RPC budget it was configured with.
 */
class ServiceRegistryFailureTests : StringSpec() {
    /**
     * A client that never completes a lease grant: [pending] leaves the future
     * hanging the way an unreachable etcd does, otherwise the grant fails with a
     * retriable status so the RPC retry policy runs to exhaustion.
     */
    private class GrantFailureMocks(
      private val pending: Boolean = false,
    ) {
        val lease = mockk<Lease>()
        val client: Client

        init {
            every { lease.grant(any()) } answers {
                if (pending)
                    CompletableFuture<LeaseGrantResponse>()
                else
                    CompletableFuture.failedFuture(
                        EtcdExceptionFactory.newEtcdException(ErrorCode.UNAVAILABLE, "etcdserver: no leader"),
                    )
            }
            every { lease.revoke(any()) } returns CompletableFuture.completedFuture(mockk<LeaseRevokeResponse>())
            client = mockk { every { leaseClient } returns lease }
        }
    }

    // Runs [body] on a worker thread and returns what it threw, failing the test if
    // it has not returned within [limit] — a hung registration must not hang the suite.
    private fun failureWithin(
      limit: Duration,
      body: () -> Unit,
    ): Throwable {
        var thrown: Throwable? = null
        val worker = Thread { thrown = runCatching(body).exceptionOrNull() }.apply { isDaemon = true }
        worker.start()
        worker.join(limit.inWholeMilliseconds)
        check(!worker.isAlive) { "registerService() did not return within $limit" }
        return thrown ?: error("expected registerService() to fail")
    }

    init {
        // The catch in registerService() used to relabel every failure as a lost CAS
        // and drop the cause, so an unreachable etcd was indistinguishable from a
        // duplicate key — the reason a failing run reports nothing actionable.
        "a registration that fails on the lease grant reports the underlying cause" {
            val mocks = GrantFailureMocks()
            val registry = ServiceRegistry(mocks.client, "/discovery/$SPEC")

            val thrown =
                shouldThrow<EtcdRecipeException> {
                    registry.registerService(serviceInstance("test-service", "payload"))
                }

            thrown.cause.shouldNotBeNull().message.shouldNotBeNull() shouldContain "leaseGrant"
        }

        "a registration that loses the CAS reports that the instance key already exists" {
            val mocks = FailingLeaseMocks(leaseId = 77L)
            val registry = ServiceRegistry(mocks.client, "/discovery/$SPEC")

            val thrown =
                shouldThrow<EtcdRecipeException> {
                    registry.registerService(serviceInstance("test-service", "payload"))
                }

            thrown.message.shouldNotBeNull() shouldContain "already exists"
            thrown.cause.shouldNotBeNull()
        }

        // The initial lease grant used to run under RpcResilience.DEFAULT no matter
        // what the recipe was configured with, so a registration against an
        // unreachable etcd blocked for 5 x 30s before reporting anything.
        "the initial lease grant honors the configured RPC budget" {
            val mocks = GrantFailureMocks(pending = true)
            val resilience =
                ResilienceConfig(rpc = RpcResilience(RetryPolicy.never, operationTimeout = 200.milliseconds))
            val registry = ServiceRegistry(mocks.client, "/discovery/$SPEC", resilience = resilience)

            val thrown = failureWithin(10.seconds) { registry.registerService(serviceInstance("test-service", "x")) }

            thrown.shouldBeInstanceOf<EtcdRecipeException>()
        }
    }

    companion object {
        private const val SPEC = "ServiceRegistryFailureTests"
    }
}
