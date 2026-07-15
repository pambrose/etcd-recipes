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

/**
 * Configures how a watcher created by [io.etcd.jetcd.Client.watcher] recovers from a
 * fatally dead watch stream.
 *
 * jetcd itself transparently retries *transient* stream errors (connection loss, etcd
 * restart) with revision continuity. What jetcd abandons — and this layer recovers —
 * are *fatal* deaths: compaction of the watched revision, halt-error statuses, and
 * "etcdserver: no leader". [RetryPolicy.never] disables recovery, restoring the
 * pre-0.12 behavior where a fatally dead watch was silently dropped.
 */
class WatchResilience
  @JvmOverloads
  constructor(
    val retryPolicy: RetryPolicy = RetryPolicy.exponentialBackoff(),
    /**
     * Ask etcd for periodic progress notifications so the resume revision stays fresh
     * on quiet keys. Progress notifications are delivered to the watch block as
     * [io.etcd.jetcd.watch.WatchResponse]s with an empty event list.
     */
    val progressNotify: Boolean = true,
    val metrics: EtcdMetrics = EtcdMetrics.NoOp,
  ) {
    fun withMetrics(metrics: EtcdMetrics): WatchResilience = WatchResilience(retryPolicy, progressNotify, metrics)

    companion object {
      @JvmField
      val DEFAULT = WatchResilience()

      @JvmField
      val DISABLED = WatchResilience(RetryPolicy.never, progressNotify = false)
    }
  }

/**
 * Events describing the lifecycle of a resilient watcher's recovery machinery,
 * delivered to a [WatchRecoveryListener].
 */
sealed interface WatchRecoveryEvent {
  val watchedKey: String

  /** The underlying watch stream reported an error; recovery may follow. */
  data class Suspended(
    override val watchedKey: String,
    val cause: Throwable,
  ) : WatchRecoveryEvent

  /**
   * A replacement watch was established, resuming at [resumeRevision]
   * (0 means "current revision" — nothing had been observed yet).
   */
  data class Resubscribed(
    override val watchedKey: String,
    val resumeRevision: Long,
  ) : WatchRecoveryEvent

  /**
   * The watched revision was compacted away ([compactRevision]); the watch was
   * re-anchored at [anchorRevision]. Events between the two were lost — a caller
   * maintaining derived state must have reconciled it in the resync hook.
   */
  data class Resynced(
    override val watchedKey: String,
    val compactRevision: Long,
    val anchorRevision: Long,
  ) : WatchRecoveryEvent

  /** Recovery was abandoned (retry policy exhausted); the watcher is dead. */
  data class Failed(
    override val watchedKey: String,
    val cause: Throwable?,
  ) : WatchRecoveryEvent
}

fun interface WatchRecoveryListener {
  fun onRecoveryEvent(event: WatchRecoveryEvent)
}
