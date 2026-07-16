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

package io.etcd.recipes.cache

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TimeSource

@JvmOverloads
fun <T, R> withNodeCache(
  client: Client,
  key: String,
  codec: EtcdCodec<T>,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  receiver: NodeCache<T>.() -> R,
): R = NodeCache(client, key, codec, resilience).use { it.receiver() }

/**
 * A watch-backed cache of a **single** etcd key, decoded through [codec]. The counterpart to
 * [PathChildrenCache] (which caches a whole prefix) for the "keep one config value hot, tell me
 * when it changes" case.
 *
 * [start] snapshots the key and anchors the watch at the snapshot revision + 1, so the stream has
 * zero gap and zero overlap with the snapshot (the standard etcd bootstrap, and the same
 * establishment-race fix applied elsewhere in this library). Thereafter [current] reflects the
 * live value and registered [NodeCacheListener]s are notified of each change. Compaction of the
 * watched revision re-syncs transparently.
 */
class NodeCache<T>
  @JvmOverloads
  constructor(
    client: Client,
    val key: String,
    private val codec: EtcdCodec<T>,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : EtcdConnector(client, resilience) {
    // Plain var guarded by @Synchronized start()/doClose() (mirrors ServiceCache's `watcher`).
    private var watcher: Watch.Watcher? = null

    @Volatile
    private var latestBytes: ByteSequence? = null
    private val listeners: MutableList<NodeCacheListener<T>> = CopyOnWriteArrayList()
    private val recoveryListeners: MutableList<WatchRecoveryListener> = CopyOnWriteArrayList()

    init {
      require(key.isNotEmpty()) { "Node cache key cannot be empty" }
    }

    override val exceptionContext get() = "NodeCache[$key]"

    /** Snapshots the key and starts the watch. One-shot; throws if called twice or after close. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun start(): NodeCache<T> {
      if (startCalled.load()) throw EtcdRecipeRuntimeException("start() already called")
      checkCloseNotCalled()

      val anchorRevision = reconcile()

      // Single-key watch (no isPrefix), anchored just past the snapshot revision.
      val watchOption = watchOption { withRevision(anchorRevision) }

      watcher =
        client.watcher(
          key,
          watchOption,
          resilience.watch,
          recoveryListener = { event -> onRecoveryEvent(event) },
          resyncWith = { reconcile() },
        ) { watchResponse ->
          withRecipeLoggingContext {
            watchResponse.events.forEach { event ->
              when (event.eventType) {
                WatchEvent.EventType.PUT -> {
                  val created = latestBytes == null
                  latestBytes = event.keyValue.value
                  fire(if (created) NodeCacheEvent.Type.CREATED else NodeCacheEvent.Type.UPDATED, event.keyValue.value)
                }

                WatchEvent.EventType.DELETE -> {
                  latestBytes = null
                  fire(NodeCacheEvent.Type.DELETED, null)
                }

                WatchEvent.EventType.UNRECOGNIZED -> {
                  logger.error { "Unrecognized error with $key watch" }
                }

                else -> {
                  logger.error { "Unknown error with $key watch" }
                }
              }
            }
          }
        }

      startCalled.store(true)
      startThreadComplete.set(true)
      return this
    }

    /** The current decoded value, or null when the key is absent. Decodes on read (may throw on a malformed value). */
    val current: T? get() = latestBytes?.let { codec.decode(it) }

    /** The current raw value, or null when the key is absent. */
    val currentBytes: ByteSequence? get() = latestBytes

    fun addListener(listener: NodeCacheListener<T>) {
      listeners += listener
    }

    fun removeListener(listener: NodeCacheListener<T>) {
      listeners -= listener
    }

    fun clearListeners() = listeners.clear()

    fun addRecoveryListener(listener: WatchRecoveryListener) {
      recoveryListeners += listener
    }

    fun removeRecoveryListener(listener: WatchRecoveryListener) {
      recoveryListeners -= listener
    }

    // Snapshot the key, capturing the revision so the watch can anchor at revision + 1. Reused as
    // the compaction resync hook (deliberately not synchronized — runs on the watch dispatcher).
    private fun reconcile(): Long {
      val start = TimeSource.Monotonic.markNow()
      val resp = client.getResponse(key, rpc = resilience.rpc)
      latestBytes = resp.kvs.firstOrNull()?.value
      resilience.metrics.recordCacheSync(key, start.elapsedNow(), if (latestBytes == null) 0 else 1)
      return resp.header.revision + 1
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fire(
      type: NodeCacheEvent.Type,
      bytes: ByteSequence?,
    ) {
      // A malformed payload is recorded and the event skipped, rather than killing the dispatcher.
      val value = bytes?.let {
        runCatching { codec.decode(it) }.getOrElse { e ->
        recordException(e)
        return
      }
      }
      listeners.forEach { listener ->
        try {
          listener.nodeChanged(NodeCacheEvent(type, value))
        } catch (e: Throwable) {
          logger.error(e) { "Exception in nodeChanged()" }
          recordException(e)
        }
      }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onRecoveryEvent(event: WatchRecoveryEvent) {
      withRecipeLoggingContext {
        reportRecoveryEvent(event)
        if (event is WatchRecoveryEvent.Failed) {
          recordException(
            event.cause ?: EtcdRecipeRuntimeException("Watch on $key abandoned; node cache is no longer updating"),
          )
        }
        recoveryListeners.forEach { listener ->
          try {
            listener.onRecoveryEvent(event)
          } catch (e: Throwable) {
            logger.error(e) { "Exception in recovery listener" }
            recordException(e)
          }
        }
      }
    }

    @Synchronized
    override fun doClose() {
      checkStartCalled()
      watcher?.close()
      watcher = null
      listeners.clear()
    }

    companion object {
      private val logger = KotlinLogging.logger {}
    }
  }
