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
import io.etcd.jetcd.support.CloseableClient
import io.etcd.jetcd.support.Observers
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.DurationUnit

private val logger = KotlinLogging.logger {}

fun <T> Client.keepAliveWith(
  lease: LeaseGrantResponse,
  block: () -> T,
): T = keepAlive(lease).use { block.invoke() }

fun Client.keepAlive(lease: LeaseGrantResponse): CloseableClient =
  leaseClient.keepAlive(
    lease.id,
    Observers.observer { next -> logger.debug { "KeepAlive next resp: $next" } },
  )

fun Client.leaseGrant(ttl: Duration): LeaseGrantResponse =
  leaseClient.grant(ttl.toDouble(DurationUnit.SECONDS).toLong()).get()

/**
 * Best-effort revoke of a lease. Failures are logged and swallowed because
 * callers use this on cleanup paths (failed CAS, exception in put loop) where
 * raising a secondary failure would mask the original problem; the lease's TTL
 * is the upper bound on resource retention if the revoke RPC itself fails.
 */
fun Client.leaseRevoke(lease: LeaseGrantResponse) {
  try {
    leaseClient.revoke(lease.id).get()
  } catch (e: Throwable) {
    logger.debug(e) { "leaseRevoke(${lease.id}) failed; lease will expire on TTL" }
  }
}
