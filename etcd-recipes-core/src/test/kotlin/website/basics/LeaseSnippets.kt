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

package website.basics

import io.etcd.jetcd.Client
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.keepAlive
import io.etcd.recipes.common.keepAliveWith
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.leaseRevoke
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.putValueWithKeepAlive
import io.etcd.recipes.common.putValuesWithKeepAlive
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun grantAndBind(client: Client) {
  // --8<-- [start:grant]
  // A lease is a timer that lives on the etcd server, not in your process. Bind a key to
  // it and etcd deletes that key the instant the lease expires — no client needs to be
  // alive, reachable, or willing for that to happen.
  val lease = client.leaseGrant(5.seconds)
  client.putValue("/services/worker-1", "10.0.0.1:8080", putOption { withLeaseId(lease.id) })

  // Nothing is renewing it, so the key is gone roughly 5 seconds from now.
  logger.info { "Lease ${lease.id} granted with a ${lease.ttl}s TTL" }
  // --8<-- [end:grant]
}

fun renewWhileWorking(client: Client) {
  // --8<-- [start:keep-alive]
  val lease = client.leaseGrant(5.seconds)
  client.putValue("/services/worker-1", "10.0.0.1:8080", putOption { withLeaseId(lease.id) })

  // keepAliveWith renews in the background for exactly as long as the block runs, then
  // stops. onKeepAliveError fires if the renewal stream dies while you are still inside
  // — without it, renewal can stop silently and the key vanishes while you look healthy.
  client.keepAliveWith(lease, onKeepAliveError = { e -> logger.error(e) { "Renewal stopped" } }) {
    logger.info { "Registered for the duration of this block" }
  }

  // Best-effort by design: a revoke that fails is logged, not thrown, because the TTL is
  // already the upper bound on how long the lease can outlive you.
  client.leaseRevoke(lease)
  // --8<-- [end:keep-alive]
}

fun renewUnscoped(client: Client) {
  // --8<-- [start:keep-alive-raw]
  val lease = client.leaseGrant(5.seconds)

  // The unscoped form, when the lease must outlive the current block. You own the close:
  // renewal continues until the returned CloseableClient is closed.
  val renewal = client.keepAlive(lease) { e -> logger.error(e) { "Renewal stopped" } }
  renewal.use {
    logger.info { "Renewing lease ${lease.id}" }
  }
  // --8<-- [end:keep-alive-raw]
}

fun putWithKeepAlive(client: Client) {
  // --8<-- [start:put-with-keep-alive]
  // Grant, put, renew, and revoke collapsed into one call: the key lives exactly as long
  // as the block. If a put throws on the way in, the lease is revoked rather than
  // stranded for its TTL. This is the shape every ephemeral recipe is built from.
  client.putValueWithKeepAlive("/services/worker-1", "10.0.0.1:8080", 5L) {
    logger.info { "Serving; the registration key is renewed underneath us" }
  }
  // --8<-- [end:put-with-keep-alive]
}

fun putSeveralWithKeepAlive(client: Client) {
  // --8<-- [start:put-values-with-keep-alive]
  // Several keys on ONE lease, so they appear together and vanish together. A reader can
  // never catch half a registration published and half of it expired.
  val kvs =
    [
      "/services/worker-1/host" to "10.0.0.1".asByteSequence,
      "/services/worker-1/port" to 8080.asByteSequence,
    ]

  client.putValuesWithKeepAlive(kvs, 5L, onKeepAliveError = { e -> logger.error(e) { "Renewal stopped" } }) {
    logger.info { "Both keys are alive for exactly this block" }
  }
  // --8<-- [end:put-values-with-keep-alive]
}
