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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configures retry and timeout behavior for the blocking RPC calls in the
 * extension layer ([putValue], [getValue], [deleteKey], [leaseGrant], ...).
 *
 * Pre-0.12, every RPC blocked on an unbounded `future.get()` — with jetcd's
 * default `waitForReady` semantics, a call issued against an unreachable server
 * parked forever. [operationTimeout] bounds each attempt, and [retryPolicy]
 * retries attempts that failed with a retriable status (UNAVAILABLE, INTERNAL,
 * DEADLINE_EXCEEDED) or timed out.
 *
 * Transactions ([transaction]) are never retried regardless of policy: a failed
 * commit is ambiguous (it may have been applied), and compare-and-swap retry
 * decisions belong to the recipes' own CAS loops. The timeout still applies.
 */
class RpcResilience
  @JvmOverloads
  constructor(
    val retryPolicy: RetryPolicy = RetryPolicy.bounded(maxAttempts = 4, delay = 250.milliseconds),
    /** Per-attempt deadline; [Duration.INFINITE] restores unbounded waiting. */
    val operationTimeout: Duration = 30.seconds,
  ) {
    companion object {
      @JvmField
      val DEFAULT = RpcResilience()

      @JvmField
      val DISABLED = RpcResilience(RetryPolicy.never, operationTimeout = Duration.INFINITE)
    }
  }
