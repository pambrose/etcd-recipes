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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shared test helpers must fail with a message when something never finishes, not
 * park the test forever: Kotest's timeout is coroutine-based and cannot interrupt a
 * thread blocked in `CountDownLatch.await()`, so an unbounded wait turned a deadlock
 * regression into a hang that burned the whole CI job.
 */
class TestExtensionsTests : StringSpec() {
  init {
    "blockingThreads fails instead of hanging when a thread never finishes" {
      val release = CountDownLatch(1)
      try {
        val e = shouldThrow<AssertionError> {
          blockingThreads(2, timeout = 300.milliseconds) { index -> if (index == 0) release.await() }
        }
        e.message shouldContain "1 of 2 threads"
      } finally {
        release.countDown()
      }
    }

    "awaitOrFail fails with a message when the latch never opens" {
      val e = shouldThrow<AssertionError> { CountDownLatch(1).awaitOrFail(200.milliseconds, "the consumer") }
      e.message shouldContain "the consumer"
    }

    "waitForAll fails when one latch never opens" {
      val open = CountDownLatch(0)
      val stuck = CountDownLatch(1)
      shouldThrow<AssertionError> { [open, stuck].waitForAll(200.milliseconds) }
    }
  }
}
