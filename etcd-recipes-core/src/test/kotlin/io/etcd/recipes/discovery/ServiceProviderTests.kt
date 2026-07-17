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
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.kv.GetResponse
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.pollUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ServiceProviderTests : StringSpec() {
    private fun clientReturning(kvs: List<KeyValue>): Client {
        val getResp = mockk<GetResponse>()
        every { getResp.kvs } returns kvs
        every { getResp.isMore } returns false
        val kv = mockk<KV>()
        every { kv.get(any(), any()) } returns CompletableFuture.completedFuture(getResp)
        return mockk { every { kvClient } returns kv }
    }

    // Build a mocked client whose ranged GET returns these instances (unstarted read path).
    private fun clientReturningInstances(instances: List<ServiceInstance>): Client =
        clientReturning(
            instances.mapIndexed { i, instance ->
                mockk<KeyValue> {
                    every { key } returns "/services/names/my-service/id$i".asByteSequence
                    every { value } returns instance.toJson().asByteSequence
                }
            },
        )

    init {
        // Regression test for #10: an empty result is an ordinary discovery condition,
        // so getInstance() must throw the library's typed exception (naming the
        // service) instead of a bare, context-free NoSuchElementException from random().
        "getInstance throws a typed EtcdRecipeException naming the service when none are registered" {
            val provider = ServiceProvider(clientReturning(emptyList()), "/services/names", "my-service")

            val ex = shouldThrow<EtcdRecipeException> { provider.getInstance() }
            ex.message shouldContain "my-service"
        }

        "getInstance returns a registered instance when one is present" {
            val instance = serviceInstance("my-service", "payload")
            val kv =
                mockk<KeyValue> {
                    every { key } returns "/services/names/my-service/abc".asByteSequence
                    every { value } returns instance.toJson().asByteSequence
                }
            val provider = ServiceProvider(clientReturning(listOf(kv)), "/services/names", "my-service")

            provider.getInstance().name shouldBe "my-service"
        }

        "round-robin distributes getInstance across all instances" {
            val instances = (1..3).map { serviceInstance("my-service", "payload-$it") }
            val provider =
                ServiceProvider(
                    clientReturningInstances(instances),
                    "/services/names",
                    "my-service",
                    strategy = RoundRobinStrategy(),
                )

            val picks = (1..3).map { provider.getInstance() }
            picks shouldContainExactlyInAnyOrder instances
        }

        "noteError ejects an instance until its down window elapses, then it recovers" {
            val alive = serviceInstance("my-service", "alive")
            val failing = serviceInstance("my-service", "failing")
            val provider =
                ServiceProvider(
                    clientReturningInstances(listOf(alive, failing)),
                    "/services/names",
                    "my-service",
                    strategy = RoundRobinStrategy(),
                    errorThreshold = 1,
                    downPeriod = 500.milliseconds,
                )

            provider.noteError(failing) // threshold 1 -> ejected immediately

            // Within the window only the healthy instance is ever selected.
            repeat(10) { provider.getInstance() shouldBe alive }

            // After the window the ejected instance becomes eligible again.
            pollUntil(5.seconds) { (1..4).map { provider.getInstance() }.contains(failing) } shouldBe true
        }
    }
}
