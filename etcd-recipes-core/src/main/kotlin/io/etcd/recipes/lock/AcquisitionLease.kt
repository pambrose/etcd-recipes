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
import io.etcd.recipes.common.isLeaseNotFound
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.leaseRevoke
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
 * [onTransient]. Neither callback fires on our own close.
 *
 * [close] revokes — revocation is the sole authoritative abort of a server-side
 * lock wait (jetcd internally retries the lock RPC on safe-redo failures, and
 * future cancellation is best-effort only). [closeWithoutRevoke] is for the
 * already-lost path, where the lease is gone and callbacks run on jetcd's
 * threads (no blocking revoke RPC there).
 */
internal class AcquisitionLease(
  private val client: Client,
  ttlSecs: Long,
  private val rpc: RpcResilience,
  onTransient: (Throwable) -> Unit,
  onFatal: (Throwable?) -> Unit,
) : Closeable {
  private val lease = client.leaseGrant(ttlSecs.seconds, rpc)
  private val closed = AtomicBoolean(false)

  val leaseId: Long get() = lease.id

  private val registration: CloseableClient =
    client.leaseClient.keepAlive(
      lease.id,
      Observers.builder<LeaseKeepAliveResponse>()
        .onNext { }
        .onError { e ->
          if (!closed.load()) {
            if (e.isLeaseNotFound()) onFatal(e) else onTransient(e)
          }
        }
        .onCompleted {
          if (!closed.load()) onFatal(null)
        }
        .build(),
    )

  /** Stops renewal without a revoke RPC — for when the lease is already gone. */
  fun closeWithoutRevoke() {
    if (closed.compareAndSet(false, true)) {
      registration.close()
    }
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      registration.close()
      client.leaseRevoke(lease, rpc) // best-effort; deletes the entry / aborts the wait
    }
  }
}
