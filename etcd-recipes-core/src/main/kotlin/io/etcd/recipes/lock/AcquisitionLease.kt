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

package io.etcd.recipes.lock

import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.jetcd.support.Observers
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.awaitRpc
import io.etcd.recipes.common.isLeaseNotFound
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.retryRpc
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * The lease behind one lock/permit acquisition: granted eagerly, kept alive from
 * the moment of grant (a queued waiter's lease must stay renewed — etcd aborts a
 * waiter whose lease dies), and **never self-healed**: an expired lock lease means
 * etcd has already promoted the next waiter, and reclaiming would race the new
 * holder — the same rationale as leadership step-down.
 *
 * The observer discriminates jetcd's signals: `onCompleted` (lease outlived its
 * TTL unrenewed) or a lease-not-found error mean the lease is gone → [onFatal];
 * any other stream error is transient (jetcd restarts the stream itself) →
 * [onTransient], and the first renewal after one → [onResumed]. None of them fires on
 * our own close. They run on jetcd's threads, so they must not block.
 *
 * [close] revokes — revocation is the sole authoritative abort of a server-side
 * lock wait (jetcd internally retries the lock RPC on safe-redo failures, and
 * future cancellation is best-effort only). [closeWithoutRevoke] is for the
 * already-lost path, where the lease is gone and callbacks run on jetcd's
 * threads (no blocking revoke RPC there).
 */
private val logger = KotlinLogging.logger {}

internal class AcquisitionLease(
  private val client: Client,
  ttlSecs: Long,
  private val rpc: RpcResilience,
  onTransient: (leaseId: Long, Throwable) -> Unit,
  onResumed: (leaseId: Long) -> Unit,
  onFatal: (Throwable?) -> Unit,
) : Closeable {
  private val lease = client.leaseGrant(ttlSecs.seconds, rpc)
  private val closed = AtomicBoolean(false)

  // Set by a transient stream error, cleared (with onResumed) by the next renewal
  private val suspended = AtomicBoolean(false)

  val leaseId: Long get() = lease.id

  private val registration: CloseableClient =
    client.leaseClient.keepAlive(
      lease.id,
      Observers.builder<LeaseKeepAliveResponse>()
        .onNext {
          rpc.metrics.incrementKeepAlive("renewal", lease.id)
          if (suspended.compareAndSet(true, false) && !closed.load()) {
            rpc.metrics.incrementKeepAlive("restored", lease.id)
            onResumed(lease.id)
          }
        }
        .onError { e ->
          if (!closed.load()) {
            if (e.isLeaseNotFound()) {
              rpc.metrics.incrementKeepAlive("expired", lease.id)
              onFatal(e)
            } else {
              suspended.store(true)
              rpc.metrics.incrementKeepAlive("suspended", lease.id)
              onTransient(lease.id, e)
            }
          }
        }
        .onCompleted {
          if (!closed.load()) {
            rpc.metrics.incrementKeepAlive("expired", lease.id)
            onFatal(null)
          }
        }
        .build(),
    )

  /** Stops renewal without a revoke RPC — for when the lease is already gone. */
  fun closeWithoutRevoke() {
    if (closed.compareAndSet(false, true)) {
      registration.close()
    }
  }

  /**
   * Stops renewal and revokes the lease, which deletes its entry (a release, or the abort of a
   * wait). The revoke is retried on retriable failures: one lost revoke would otherwise leave the
   * entry blocking every successor until the lease's TTL runs out. Failures are logged, not
   * thrown, since the lease still expires on its own.
   */
  @Suppress("TooGenericExceptionCaught")
  override fun close() {
    if (closed.compareAndSet(false, true)) {
      registration.close()
      try {
        retryRpc(rpc, "leaseRevoke(${lease.id})") { client.leaseClient.revoke(lease.id) }
      } catch (e: Exception) {
        logger.debug(e) { "leaseRevoke(${lease.id}) failed; the lease will expire on its TTL" }
      }
    }
  }

  /**
   * Like [close], but the revoke gets a single short attempt ([RpcResilience.PROBE]) — for an
   * acquisition that must return near its deadline. Normally the entry is still gone when this
   * returns; during an etcd brownout it overruns by at most that attempt, and a failed revoke
   * leaves the lease to its TTL.
   */
  @Suppress("TooGenericExceptionCaught")
  fun closePromptly() {
    if (closed.compareAndSet(false, true)) {
      registration.close()
      try {
        awaitRpc(
          RpcResilience.PROBE.withMetrics(rpc.metrics),
          "leaseRevoke(${lease.id})",
          client.leaseClient.revoke(lease.id),
        )
      } catch (e: Exception) {
        logger.debug(e) { "leaseRevoke(${lease.id}) failed; the lease will expire on its TTL" }
      }
    }
  }
}
