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

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Lock
import io.etcd.jetcd.Txn
import io.etcd.jetcd.Watch
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseRevokeResponse
import io.etcd.jetcd.lock.LockResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.WatchOption
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.fetchAndDecrement

/**
 * A real [Client] that runs a one-shot hook at a chosen jetcd call, so a test can land
 * an action — typically a recipe's `close()` — at an exact point inside a recipe method
 * instead of racing it with sleeps. Each hook fires once, on the calling thread, and is
 * then cleared. Everything else goes straight to [delegate].
 */
class HookedClient(
  private val delegate: Client,
) : Client by delegate {
  /** Runs before the next `kvClient.txn()`. */
  val beforeTxn = AtomicReference<(() -> Unit)?>(null)

  /** Runs before the next `kvClient.get(key, option)`. */
  val beforeGet = AtomicReference<(() -> Unit)?>(null)

  /**
   * Runs after the next `kvClient.get(key, option)` has its response, before the caller
   * sees it. (That call blocks for the response, so the hook never runs on a gRPC thread.)
   */
  val afterGet = AtomicReference<(() -> Unit)?>(null)

  /** Runs before the next single-key `kvClient.delete(key)`. */
  val beforeDelete = AtomicReference<(() -> Unit)?>(null)

  /** Runs before the next single-argument `leaseClient.grant(ttl)`. */
  val beforeLeaseGrant = AtomicReference<(() -> Unit)?>(null)

  /** While set, `leaseClient.grant(ttl)` returns a future that never completes (an etcd brownout). */
  val hangLeaseGrants = AtomicBoolean(false)

  /** The next this-many `leaseClient.revoke(id)` calls fail with UNAVAILABLE. */
  val failLeaseRevokes = AtomicInt(0)

  /** While set, `lockClient.lock(name, leaseId)` fails with this. */
  val failLocks = AtomicReference<Throwable?>(null)

  /** Every `watchClient.watch(key, option, listener)` call's key and option, in order. */
  val watchOptions = CopyOnWriteArrayList<Pair<String, WatchOption>>()

  /** Runs after the next `watchClient.watch(key, option, listener)` returns its watcher. */
  val afterWatch = AtomicReference<(() -> Unit)?>(null)

  private val kv =
    object : KV by delegate.kvClient {
      override fun txn(): Txn {
        beforeTxn.exchange(null)?.invoke()
        return delegate.kvClient.txn()
      }

      override fun get(
        key: ByteSequence,
        option: GetOption,
      ): CompletableFuture<GetResponse> {
        beforeGet.exchange(null)?.invoke()
        val after = afterGet.exchange(null) ?: return delegate.kvClient.get(key, option)
        val response = delegate.kvClient.get(key, option).get()
        after()
        return CompletableFuture.completedFuture(response)
      }

      override fun delete(key: ByteSequence): CompletableFuture<DeleteResponse> {
        beforeDelete.exchange(null)?.invoke()
        return delegate.kvClient.delete(key)
      }
    }

  private val lease =
    object : Lease by delegate.leaseClient {
      override fun grant(ttl: Long): CompletableFuture<LeaseGrantResponse> {
        beforeLeaseGrant.exchange(null)?.invoke()
        if (hangLeaseGrants.load()) return CompletableFuture()
        return delegate.leaseClient.grant(ttl)
      }

      override fun revoke(leaseId: Long): CompletableFuture<LeaseRevokeResponse> =
        if (failLeaseRevokes.fetchAndDecrement() > 0) {
          CompletableFuture.failedFuture(StatusRuntimeException(Status.UNAVAILABLE.withDescription("revoke refused")))
        } else {
          delegate.leaseClient.revoke(leaseId)
        }
    }

  private val watch =
    object : Watch by delegate.watchClient {
      override fun watch(
        key: ByteSequence,
        option: WatchOption,
        listener: Watch.Listener,
      ): Watch.Watcher {
        watchOptions += key.asString to option
        return delegate.watchClient.watch(key, option, listener).also { afterWatch.exchange(null)?.invoke() }
      }
    }

  override fun getKVClient(): KV = kv

  override fun getLeaseClient(): Lease = lease

  private val lock =
    object : Lock by delegate.lockClient {
      override fun lock(
        name: ByteSequence,
        leaseId: Long,
      ): CompletableFuture<LockResponse> =
        failLocks.load()?.let { CompletableFuture.failedFuture(it) } ?: delegate.lockClient.lock(name, leaseId)
    }

  override fun getLockClient(): Lock = lock

  override fun getWatchClient(): Watch = watch
}
