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

@file:JvmName("LeaseUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.jetcd.support.Observers
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.DurationUnit

private val logger = KotlinLogging.logger {}

fun <T> Client.keepAliveWith(
  lease: LeaseGrantResponse,
  onKeepAliveError: (Throwable) -> Unit = {},
  block: () -> T,
): T = keepAlive(lease, onKeepAliveError).use { block.invoke() }

// onNext stays at debug (one entry per renewal is noisy). [onKeepAliveError] means
// "renewal stopped: the lease and its keys will expire on TTL", which jetcd reports two
// ways: onCompleted (the lease outlived its TTL unrenewed) and onError NOT_FOUND
// "requested lease not found". Any other onError is transient — jetcd restarts the stream
// itself with renewal continuing — so it is logged at warn and does not fire the callback;
// a holder tearing down on it would react to a harmless blip. Neither fires on our own
// CloseableClient.close(); onCompleted synthesizes a throwable for the callback.
@JvmOverloads
fun Client.keepAlive(
  lease: LeaseGrantResponse,
  onKeepAliveError: (Throwable) -> Unit = {},
): CloseableClient =
  leaseClient.keepAlive(
    lease.id,
    Observers.builder<LeaseKeepAliveResponse>()
      .onNext { next -> logger.debug { "KeepAlive next resp: $next" } }
      .onError { e ->
        if (e.isLeaseNotFound()) {
          logger.error(e) { "Lease ${lease.id} not found; renewal stopped" }
          onKeepAliveError(e)
        } else {
          logger.warn(e) { "KeepAlive stream for lease ${lease.id} errored; jetcd restarts it and renewal continues" }
        }
      }
      .onCompleted {
        logger.warn { "KeepAlive completed for lease ${lease.id}; renewal stopped, lease expires on TTL" }
        onKeepAliveError(EtcdRecipeRuntimeException("KeepAlive renewal stopped for lease ${lease.id}"))
      }
      .build(),
  )

// Retried on retriable statuses: a duplicate grant from an ambiguous first attempt
// orphans a lease that dies at its TTL — harmless.
@JvmOverloads
fun Client.leaseGrant(
  ttl: Duration,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): LeaseGrantResponse =
  retryRpc(rpc, "leaseGrant($ttl)") { leaseClient.grant(ttl.toDouble(DurationUnit.SECONDS).toLong()) }

/**
 * Best-effort revoke of a lease. Failures are logged and swallowed because
 * callers use this on cleanup paths (failed CAS, exception in put loop) where
 * raising a secondary failure would mask the original problem; the lease's TTL
 * is the upper bound on resource retention if the revoke RPC itself fails.
 * The operation timeout applies so cleanup paths cannot park forever.
 */
@Suppress("TooGenericExceptionCaught")
@JvmOverloads
fun Client.leaseRevoke(
  lease: LeaseGrantResponse,
  rpc: RpcResilience = RpcResilience.DEFAULT,
) {
  try {
    awaitRpc(rpc, "leaseRevoke(${lease.id})", leaseClient.revoke(lease.id))
  } catch (e: Throwable) {
    logger.debug(e) { "leaseRevoke(${lease.id}) failed; lease will expire on TTL" }
  }
}
