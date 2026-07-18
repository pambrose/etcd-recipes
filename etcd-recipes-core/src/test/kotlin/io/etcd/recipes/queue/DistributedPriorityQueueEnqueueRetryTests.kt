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

package io.etcd.recipes.queue

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Response
import io.etcd.jetcd.Txn
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

class DistributedPriorityQueueEnqueueRetryTests : StringSpec() {
    // Builds a Client whose getLastChild always reports an empty prefix and whose
    // CAS (txn.commit) result is scripted by [casOutcomes]; returnsMany repeats the
    // last element, so a single `false` makes every attempt lose. Returns the Txn
    // mock too so a test can verify how many enqueue attempts were made.
    private fun scriptedClient(casOutcomes: List<Boolean>): Pair<Client, Txn> {
        val header = mockk<Response.Header>()
        every { header.revision } returns 0L

        val getResp = mockk<GetResponse>()
        every { getResp.kvs } returns []
        every { getResp.isMore } returns false
        every { getResp.header } returns header

        val txn = mockk<Txn>()
        every { txn.If(*anyVararg()) } returns txn
        every { txn.Then(*anyVararg()) } returns txn
        every { txn.commit() } returnsMany
            casOutcomes.map { succeeded ->
                CompletableFuture.completedFuture(mockk<TxnResponse> { every { isSucceeded } returns succeeded })
            }

        val kv = mockk<KV>()
        every { kv.get(any<ByteSequence>(), any<GetOption>()) } returns CompletableFuture.completedFuture(getResp)
        every { kv.txn() } returns txn

        val client = mockk<Client>()
        every { client.kvClient } returns kv

        return client to txn
    }

    init {
        // Regression test: a same-priority enqueue that loses the optimistic CAS to
        // another (cross-instance) producer must retry with a fresh sequence number,
        // not surface the conflict as an exception to the caller.
        "retries the enqueue CAS and succeeds once an attempt wins" {
            val (client, txn) = scriptedClient([false, false, true])
            val queue = DistributedPriorityQueue(client, "/pqueue", minimumWaitTime = 0.milliseconds)

            shouldNotThrowAny { queue.enqueue("payload", priority = 5) }

            verify(exactly = 3) { txn.commit() }
        }

        // The retry loop must be bounded so a persistently-losing enqueue surfaces a
        // failure instead of spinning forever while holding the instance monitor.
        "throws after a bounded number of attempts when the CAS never wins" {
            val (client, txn) = scriptedClient([false])
            val queue = DistributedPriorityQueue(client, "/pqueue", minimumWaitTime = 0.milliseconds)

            shouldThrow<EtcdRecipeRuntimeException> { queue.enqueue("payload", priority = 5) }

            // Proves it retried (not a single-shot failure) and that the loop terminates.
            verify(atLeast = 2) { txn.commit() }
        }
    }
}
