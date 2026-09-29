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

package io.etcd.recipes.lock

import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The shortest attempt a bounded acquisition makes, even once its deadline has passed
private val MIN_ATTEMPT = 1.milliseconds
private val TIMEOUT_SLACK = 1.milliseconds

/**
 * This RPC budget for an acquisition bounded by [deadline] (`tryLock`, `tryAcquire`): no
 * attempt outlives the deadline, and no retry starts after it. Unchanged when [deadline] is
 * null (an unbounded acquisition).
 */
internal fun RpcResilience.within(deadline: ComparableTimeMark?): RpcResilience {
  if (deadline == null) return this
  val base = retryPolicy
  // The RPC engine waits in whole milliseconds; the slack keeps an attempt that times out from
  // ending a hair before the deadline it was sized to.
  val remaining = (-deadline.elapsedNow() + TIMEOUT_SLACK).coerceAtLeast(MIN_ATTEMPT)
  return RpcResilience(
    RetryPolicy { attempt, elapsed -> base.nextDelay(attempt, elapsed)?.takeIf { it < -deadline.elapsedNow() } },
    operationTimeout = minOf(operationTimeout, remaining),
    metrics = metrics,
  )
}

/** This config with its RPC budget bounded by [deadline]; see [RpcResilience.within]. */
internal fun ResilienceConfig.within(deadline: ComparableTimeMark?): ResilienceConfig =
  if (deadline == null) this else copy(rpc = rpc.within(deadline))

/** Sleeps [pause], but not past [deadline]. */
internal fun pauseWithin(
  pause: Duration,
  deadline: ComparableTimeMark?,
) {
  val bounded = if (deadline == null) pause else minOf(pause, -deadline.elapsedNow())
  if (bounded > Duration.ZERO) Thread.sleep(bounded.inWholeMilliseconds)
}

/** Whether [deadline] (if any) has passed. */
internal fun ComparableTimeMark?.hasPassed(): Boolean = this != null && hasPassedNow()
