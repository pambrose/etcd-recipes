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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture

class ServiceProviderTests : StringSpec() {
    private fun clientReturning(kvs: List<KeyValue>): Client {
        val getResp = mockk<GetResponse>()
        every { getResp.kvs } returns kvs
        every { getResp.isMore } returns false
        val kv = mockk<KV>()
        every { kv.get(any(), any()) } returns CompletableFuture.completedFuture(getResp)
        return mockk { every { kvClient } returns kv }
    }

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
    }
}
