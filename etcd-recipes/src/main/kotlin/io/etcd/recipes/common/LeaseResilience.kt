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
import kotlin.time.Duration.Companion.seconds

/**
 * Configures how a [SelfHealingKeepAlive] recovers from lease expiry.
 *
 * jetcd auto-restarts a keep-alive stream after transient errors with the observer
 * still registered, so renewal resumes by itself. What jetcd cannot recover — and
 * this layer heals — is an *expired* lease (renewal stopped longer than the TTL, or
 * etcd reports the lease as gone): the lease must be re-granted and the owned keys
 * re-established. [RetryPolicy.never] disables healing, restoring the pre-0.12
 * behavior where an expired lease meant the recipe's keys were gone for good.
 */
class LeaseResilience
  @JvmOverloads
  constructor(
    val retryPolicy: RetryPolicy = RetryPolicy.exponentialBackoff(),
    /**
     * Per-RPC deadline for the re-grant during healing, so heal attempts fail and
     * retry under the policy instead of parking forever on an unreachable server.
     */
    val healOperationTimeout: Duration = 10.seconds,
  ) {
    companion object {
      @JvmField
      val DEFAULT = LeaseResilience()

      @JvmField
      val DISABLED = LeaseResilience(RetryPolicy.never)
    }
  }

/**
 * Events describing the lifecycle of a [SelfHealingKeepAlive], delivered to a
 * [LeaseListener].
 */
sealed interface LeaseEvent {
  /** The keep-alive stream reported a transient error; jetcd is retrying it. */
  data class Suspended(
    val leaseId: Long,
    val cause: Throwable,
  ) : LeaseEvent

  /**
   * The lease expired (renewal stopped past its TTL, or etcd reported it gone).
   * Ownership of the bound keys may have been lost to another party; healing
   * follows unless the retry policy forbids it.
   */
  data class Expired(
    val leaseId: Long,
    val cause: Throwable?,
  ) : LeaseEvent

  /** A replacement lease was granted and the owned keys were re-established. */
  data class Restored(
    val oldLeaseId: Long,
    val newLeaseId: Long,
  ) : LeaseEvent

  /** Healing was abandoned (policy exhausted, or the establish hook declined). */
  data class Failed(
    val leaseId: Long,
    val cause: Throwable?,
  ) : LeaseEvent
}

fun interface LeaseListener {
  fun onLeaseEvent(event: LeaseEvent)
}
