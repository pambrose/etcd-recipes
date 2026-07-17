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

package io.etcd.recipes.coroutines

import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.recipes.common.RpcResilience
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.DurationUnit

private val logger = KotlinLogging.logger {}

/**
 * Suspending twin of `leaseGrant`. Retried on retriable statuses: a duplicate grant
 * from an ambiguous first attempt orphans a lease that dies at its TTL — harmless.
 */
suspend fun Client.awaitLeaseGrant(
  ttl: Duration,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): LeaseGrantResponse =
  suspendRetryRpc(rpc, "leaseGrant($ttl)") { leaseClient.grant(ttl.toDouble(DurationUnit.SECONDS).toLong()) }

/**
 * Suspending twin of `leaseRevoke`: best-effort, failures are logged and swallowed
 * (callers use this on cleanup paths where a secondary failure would mask the
 * original problem; the TTL bounds resource retention if the revoke fails) — but
 * cancellation always propagates.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun Client.awaitLeaseRevoke(
  lease: LeaseGrantResponse,
  rpc: RpcResilience = RpcResilience.DEFAULT,
) {
  try {
    suspendAwaitRpc(rpc, "leaseRevoke(${lease.id})", leaseClient.revoke(lease.id))
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    logger.debug(e) { "leaseRevoke(${lease.id}) failed; lease will expire on TTL" }
  }
}
