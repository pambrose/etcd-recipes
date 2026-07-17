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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [RetryPolicy]. Pure logic — no etcd connection.
 */
class RetryPolicyTests : StringSpec() {
  init {
    "never yields null on the first attempt" {
      RetryPolicy.never.nextDelay(1, Duration.ZERO).shouldBeNull()
    }

    "bounded returns the fixed delay for maxAttempts attempts then null" {
      val policy = RetryPolicy.bounded(maxAttempts = 3, delay = 100.milliseconds)
      policy.nextDelay(1, Duration.ZERO) shouldBe 100.milliseconds
      policy.nextDelay(2, Duration.ZERO) shouldBe 100.milliseconds
      policy.nextDelay(3, Duration.ZERO) shouldBe 100.milliseconds
      policy.nextDelay(4, Duration.ZERO).shouldBeNull()
    }

    "exponentialBackoff grows by factor and caps at maxDelay" {
      val policy = RetryPolicy.exponentialBackoff(
        initialDelay = 100.milliseconds,
        maxDelay = 500.milliseconds,
        factor = 2.0,
        jitterRatio = 0.0,
      )
      policy.nextDelay(1, Duration.ZERO) shouldBe 100.milliseconds
      policy.nextDelay(2, Duration.ZERO) shouldBe 200.milliseconds
      policy.nextDelay(3, Duration.ZERO) shouldBe 400.milliseconds
      policy.nextDelay(4, Duration.ZERO) shouldBe 500.milliseconds // capped
      policy.nextDelay(10, Duration.ZERO) shouldBe 500.milliseconds // stays capped
    }

    "exponentialBackoff honors maxAttempts" {
      val policy = RetryPolicy.exponentialBackoff(jitterRatio = 0.0, maxAttempts = 2)
      policy.nextDelay(1, Duration.ZERO).shouldNotBeNull()
      policy.nextDelay(2, Duration.ZERO).shouldNotBeNull()
      policy.nextDelay(3, Duration.ZERO).shouldBeNull()
    }

    "exponentialBackoff honors maxElapsed" {
      val policy = RetryPolicy.exponentialBackoff(jitterRatio = 0.0, maxElapsed = 5.seconds)
      policy.nextDelay(1, 4.seconds).shouldNotBeNull()
      policy.nextDelay(1, 5.seconds).shouldBeNull()
      policy.nextDelay(1, 6.seconds).shouldBeNull()
    }

    "exponentialBackoff jitter stays within the configured ratio" {
      val base = 1000.milliseconds
      val policy = RetryPolicy.exponentialBackoff(
        initialDelay = base,
        maxDelay = 10.seconds,
        jitterRatio = 0.25,
      )
      repeat(200) {
        val delay = policy.nextDelay(1, Duration.ZERO)
        delay.shouldNotBeNull()
        (delay >= 750.milliseconds) shouldBe true
        (delay <= 1250.milliseconds) shouldBe true
      }
    }

    "forever keeps producing delays for large attempt counts" {
      RetryPolicy.forever.nextDelay(1, Duration.ZERO).shouldNotBeNull()
      RetryPolicy.forever.nextDelay(10_000, 365.seconds).shouldNotBeNull()
    }

    "exponentialBackoff rejects non-positive attempt numbers" {
      val policy = RetryPolicy.exponentialBackoff(jitterRatio = 0.0)
      shouldThrow<IllegalArgumentException> { policy.nextDelay(0, Duration.ZERO) }
      shouldThrow<IllegalArgumentException> { policy.nextDelay(-1, Duration.ZERO) }
    }

    "exponentialBackoff validates its configuration" {
      shouldThrow<IllegalArgumentException> { RetryPolicy.exponentialBackoff(factor = 0.5) }
      shouldThrow<IllegalArgumentException> { RetryPolicy.exponentialBackoff(jitterRatio = -0.1) }
      shouldThrow<IllegalArgumentException> { RetryPolicy.exponentialBackoff(jitterRatio = 1.1) }
      shouldThrow<IllegalArgumentException> { RetryPolicy.bounded(maxAttempts = 0) }
    }
  }
}
