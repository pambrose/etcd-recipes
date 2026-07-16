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

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryListener
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Scopes a [TypedPathChildrenCache] to [receiver], closing it on exit. */
@JvmOverloads
fun <T, R> withTypedPathChildrenCache(
  client: Client,
  cachePath: String,
  codec: EtcdCodec<T>,
  userExecutor: Executor? = null,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  receiver: TypedPathChildrenCache<T>.() -> R,
): R = TypedPathChildrenCache(client, cachePath, codec, userExecutor, resilience).use { it.receiver() }

/**
 * A typed prefix cache: a [PathChildrenCache] with every child value decoded through [codec].
 * [currentData] / [getCurrentData] / [currentDataAsMap] return [T], and registered
 * [TypedPathChildrenCacheListener]s receive decoded [TypedPathChildrenCacheEvent]s.
 *
 * A malformed payload is recorded on `untyped`'s exception sink (the underlying cache catches the
 * decode failure raised in the re-emit path) and that event is skipped; the read accessors decode
 * lazily and may throw to the caller (as [NodeCache.current] does). The full connector API stays
 * reachable through [untyped].
 */
class TypedPathChildrenCache<T>(
  val untyped: PathChildrenCache,
  private val codec: EtcdCodec<T>,
) : Closeable {
  @JvmOverloads
  constructor(
    client: Client,
    cachePath: String,
    codec: EtcdCodec<T>,
    userExecutor: Executor? = null,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : this(PathChildrenCache(client, cachePath, userExecutor, resilience), codec)

  private val listeners: MutableList<TypedPathChildrenCacheListener<T>> = CopyOnWriteArrayList()

  // A single adapter on the underlying cache decodes each event and re-emits to the typed
  // listeners. A decode failure here propagates into the underlying cache's per-listener
  // try/catch, which records it (surfaced on untyped.exceptions) and skips the event.
  private val adapter =
    PathChildrenCacheListener { event ->
      val typedEvent =
        TypedPathChildrenCacheEvent(
          event.childName,
          event.type,
          event.data?.let { codec.decode(it) },
        ).apply {
          if (event.type == PathChildrenCacheEvent.Type.INITIALIZED)
            initialDataVal = event.initialData.map { TypedChildData(it.key, codec.decode(it.value)) }
        }
      listeners.forEach { it.childEvent(typedEvent) }
    }

  init {
    untyped.addListener(adapter)
  }

  @JvmOverloads
  fun start(
    buildInitial: Boolean = false,
    waitOnStartComplete: Boolean = true,
  ): TypedPathChildrenCache<T> {
    untyped.start(buildInitial, waitOnStartComplete)
    return this
  }

  @JvmOverloads
  fun start(
    mode: PathChildrenCache.StartMode,
    waitOnStartComplete: Boolean = true,
  ): TypedPathChildrenCache<T> {
    untyped.start(mode, waitOnStartComplete)
    return this
  }

  fun waitOnStartComplete(): Boolean = untyped.waitOnStartComplete()

  fun waitOnStartComplete(timeout: Duration): Boolean = untyped.waitOnStartComplete(timeout)

  fun waitOnStartComplete(
    timeout: Long,
    timeUnit: TimeUnit,
  ): Boolean = untyped.waitOnStartComplete(timeout, timeUnit)

  val currentData: List<TypedChildData<T>>
    get() = untyped.currentData.map { TypedChildData(it.key, codec.decode(it.value)) }

  fun getCurrentData(childName: String): T? = untyped.getCurrentData(childName)?.let(codec::decode)

  val currentDataAsMap: Map<String, T>
    get() = untyped.currentDataAsMap.mapValues { (_, v) -> codec.decode(v) }

  fun addListener(listener: TypedPathChildrenCacheListener<T>) {
    listeners += listener
  }

  fun removeListener(listener: TypedPathChildrenCacheListener<T>) {
    listeners -= listener
  }

  fun clearListeners() = listeners.clear()

  fun addRecoveryListener(listener: WatchRecoveryListener) = untyped.addRecoveryListener(listener)

  fun removeRecoveryListener(listener: WatchRecoveryListener) = untyped.removeRecoveryListener(listener)

  fun rebuild() = untyped.rebuild()

  fun clear() = untyped.clear()

  override fun close() = untyped.close()
}
