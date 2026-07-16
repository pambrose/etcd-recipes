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

// onNext stays at debug (one entry per renewal is noisy), but onError/onCompleted
// must not be silent: this is the lease-liveness primitive for every recipe that
// holds a lease, and a stream that errors or completes means renewal has stopped
// and the lease (and its bound keys) will expire on TTL while the recipe still
// looks healthy. jetcd's Observers.observer { } leaves both as no-ops, so surface
// them at error/warn AND invoke [onKeepAliveError] so a holder (e.g. an
// EtcdConnector subclass) can record the failure on its exceptions list. Neither
// callback fires on our own CloseableClient.close() — both mean renewal genuinely
// stopped — so onCompleted synthesizes a throwable for the same callback.
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
        logger.error(e) { "KeepAlive stream errored for lease ${lease.id}; lease will expire on its TTL" }
        onKeepAliveError(e)
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
