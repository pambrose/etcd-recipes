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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class EtcdConnectorExceptionsTests : StringSpec() {
    private class TestConnector(
      client: Client,
    ) : EtcdConnector(client) {
        fun record(t: Throwable) {
            exceptionList.value += t
        }
    }

    init {
        // Regression test for #12: exceptions must hand back a defensive copy, not the
        // live synchronizedList. If it returned the live list, a caller iterating it
        // while a background thread appends would hit a ConcurrentModificationException.
        "exceptions returns a defensive snapshot, not the live backing list" {
            val connector = TestConnector(mockk())
            connector.record(RuntimeException("a"))
            connector.record(RuntimeException("b"))

            val snapshot = connector.exceptions
            snapshot.size shouldBe 2

            // A later append must not mutate an already-returned snapshot.
            connector.record(RuntimeException("c"))
            snapshot.size shouldBe 2
            connector.exceptions.size shouldBe 3
        }

        "exceptions is empty before anything is recorded" {
            TestConnector(mockk()).exceptions.shouldBeEmpty()
        }
    }
}
