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

import io.etcd.jetcd.Client
import io.etcd.recipes.common.asByteSequence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import kotlin.time.Duration.Companion.milliseconds

class DistributedPriorityQueuePriorityTests : StringSpec() {
    // The priority check runs as the enqueue argument is evaluated, before any etcd
    // call, so a never-stubbed mock client suffices (and confirms no I/O happens).
    private fun queue() = DistributedPriorityQueue(mockk<Client>(), "/pqueue", minimumWaitTime = 0.milliseconds)

    init {
        // Regression test for #9: out-of-range Int priorities must be rejected, not
        // silently truncated mod 65536 (70000 -> 4464, -1 -> 65535), which would file
        // the entry in the wrong sort bucket with no diagnostic. Exercises all four
        // Int-priority overloads (String/Int/Long/ByteSequence value), each of which
        // routes through toCheckedPriority() before any etcd call.
        "enqueue rejects an above-range Int priority on every typed overload" {
            val q = queue()
            shouldThrow<IllegalArgumentException> { q.enqueue("v", 70000) }
            shouldThrow<IllegalArgumentException> { q.enqueue(42, 70000) }
            shouldThrow<IllegalArgumentException> { q.enqueue(42L, 70000) }
            shouldThrow<IllegalArgumentException> { q.enqueue("v".asByteSequence, 70000) }
        }

        "enqueue rejects a negative Int priority on every typed overload" {
            val q = queue()
            shouldThrow<IllegalArgumentException> { q.enqueue("v", -1) }
            shouldThrow<IllegalArgumentException> { q.enqueue(42, -1) }
            shouldThrow<IllegalArgumentException> { q.enqueue(42L, -1) }
            shouldThrow<IllegalArgumentException> { q.enqueue("v".asByteSequence, -1) }
        }
    }
}
