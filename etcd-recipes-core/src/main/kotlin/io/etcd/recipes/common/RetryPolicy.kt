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

package io.etcd.recipes.common

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Decides whether — and after what delay — a failed operation should be retried.
 *
 * Used by the resilience layer (watch recovery, lease healing) to pace re-establishment
 * attempts. Implementations must be thread-safe; [nextDelay] may be called from
 * background recovery threads.
 */
fun interface RetryPolicy {
  /**
   * Returns the delay to wait before retry [attempt] (1-based), given the total
   * [elapsed] time since the first failure, or `null` to give up.
   */
  fun nextDelay(
    attempt: Int,
    elapsed: Duration,
  ): Duration?

  companion object {
    /**
     * Exponential backoff: `initialDelay * factor^(attempt-1)` capped at [maxDelay],
     * with ±[jitterRatio] randomization. Gives up after [maxAttempts] attempts or once
     * [maxElapsed] time has passed since the first failure.
     */
    @JvmStatic
    @JvmOverloads
    fun exponentialBackoff(
      initialDelay: Duration = 250.milliseconds,
      maxDelay: Duration = 15.seconds,
      factor: Double = 2.0,
      jitterRatio: Double = 0.25,
      maxAttempts: Int = Int.MAX_VALUE,
      maxElapsed: Duration = Duration.INFINITE,
    ): RetryPolicy {
      require(initialDelay > Duration.ZERO) { "initialDelay must be positive: $initialDelay" }
      require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must be >= initialDelay ($initialDelay)" }
      require(factor >= 1.0) { "factor must be >= 1.0: $factor" }
      require(jitterRatio in 0.0..1.0) { "jitterRatio must be in [0.0, 1.0]: $jitterRatio" }
      require(maxAttempts >= 1) { "maxAttempts must be >= 1: $maxAttempts" }
      return RetryPolicy { attempt, elapsed ->
        require(attempt >= 1) { "attempt must be >= 1: $attempt" }
        if (attempt > maxAttempts || elapsed >= maxElapsed) {
          null
        } else {
          // factor^(attempt-1) overflows to Infinity for large attempts; the cap applies either way
          val scale = factor.pow(attempt - 1)
          val base = if (scale.isFinite()) (initialDelay * scale).coerceAtMost(maxDelay) else maxDelay
          if (jitterRatio == 0.0) {
            base
          } else {
            (base * (1.0 + Random.nextDouble(-jitterRatio, jitterRatio))).coerceAtMost(maxDelay)
          }
        }
      }
    }

    /** Fixed [delay] between attempts, giving up after [maxAttempts]. */
    @JvmStatic
    @JvmOverloads
    fun bounded(
      maxAttempts: Int,
      delay: Duration = 500.milliseconds,
    ): RetryPolicy {
      require(maxAttempts >= 1) { "maxAttempts must be >= 1: $maxAttempts" }
      require(delay >= Duration.ZERO) { "delay must be >= 0: $delay" }
      return RetryPolicy { attempt, _ ->
        require(attempt >= 1) { "attempt must be >= 1: $attempt" }
        if (attempt <= maxAttempts) delay else null
      }
    }

    /** Unbounded [exponentialBackoff] with the default pacing — never gives up. */
    @JvmField
    val forever: RetryPolicy = exponentialBackoff()

    /** Always gives up — disables the recovery loop entirely. */
    @JvmField
    val never: RetryPolicy = RetryPolicy { _, _ -> null }
  }
}
