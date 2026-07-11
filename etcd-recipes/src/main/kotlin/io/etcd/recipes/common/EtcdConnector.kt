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

package io.etcd.recipes.common

import com.pambrose.common.concurrent.BooleanMonitor
import com.pambrose.common.util.randomId
import io.etcd.jetcd.Client
import java.io.Closeable
import java.util.Collections.synchronizedList
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

open class EtcdConnector(
  protected val client: Client,
  protected val resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : Closeable {
  protected val startCalled = AtomicBoolean(false)
  protected val startThreadComplete = BooleanMonitor(false)
  protected val closeCalled = AtomicBoolean(false)
  protected val exceptionList: Lazy<MutableList<Throwable>> = lazy { synchronizedList(mutableListOf<Throwable>()) }

  protected fun checkCloseNotCalled() {
    if (closeCalled.load()) throw EtcdRecipeRuntimeException("close() already called")
  }

  // Return a defensive snapshot taken under the synchronizedList's own monitor.
  // The list is appended to from background recipe threads (keep-alive onError
  // callbacks, watcher threads); synchronizedList only guards individual ops, so a
  // caller iterating the live list while a worker adds would hit a
  // ConcurrentModificationException. A bare toList() still iterates, hence the lock.
  val exceptions: List<Throwable>
    get() =
      if (exceptionList.isInitialized())
        synchronized(exceptionList.value) { exceptionList.value.toList() }
      else
        emptyList()

  val hasExceptions get() = exceptionList.isInitialized() && exceptionList.value.isNotEmpty()

  fun clearExceptions() {
    if (exceptionList.isInitialized()) exceptionList.value.clear()
  }

  protected fun checkStartCalled() {
    if (!startCalled.load()) throw EtcdRecipeRuntimeException("start() not called")
  }

  private val connectionStateRef = AtomicReference(ConnectionState.CONNECTED)
  private val connectionStateListeners = CopyOnWriteArrayList<ConnectionStateListener>()

  /**
   * Coarse connection health, derived passively from the watch-recovery and lease
   * events this connector's own streams report — see [ConnectionState].
   */
  val connectionState: ConnectionState get() = connectionStateRef.load()

  fun addConnectionStateListener(listener: ConnectionStateListener) {
    connectionStateListeners += listener
  }

  fun removeConnectionStateListener(listener: ConnectionStateListener) {
    connectionStateListeners -= listener
  }

  /** Recipes feed their watch-recovery events here to drive [connectionState]. */
  protected fun reportRecoveryEvent(event: WatchRecoveryEvent) {
    when (event) {
      is WatchRecoveryEvent.Suspended -> transitionTo(ConnectionState.SUSPENDED)
      is WatchRecoveryEvent.Resubscribed -> transitionTo(ConnectionState.RECONNECTED)
      is WatchRecoveryEvent.Resynced -> transitionTo(ConnectionState.RECONNECTED)
      is WatchRecoveryEvent.Failed -> transitionTo(ConnectionState.LOST)
    }
  }

  /** Recipes feed their lease events here to drive [connectionState]. */
  protected fun reportLeaseEvent(event: LeaseEvent) {
    when (event) {
      is LeaseEvent.Suspended -> transitionTo(ConnectionState.SUSPENDED)
      is LeaseEvent.Expired -> transitionTo(ConnectionState.LOST)
      is LeaseEvent.Restored -> transitionTo(ConnectionState.RECONNECTED)
      is LeaseEvent.Failed -> transitionTo(ConnectionState.LOST)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun transitionTo(newState: ConnectionState) {
    // exchange() makes the transition atomic; equal states are dropped so repeated
    // Suspended reports during one outage notify once. Listeners run on the
    // reporting thread — recipes report from their own dispatcher/healer threads,
    // never from jetcd's event loop.
    val previous = connectionStateRef.exchange(newState)
    if (previous == newState) return
    connectionStateListeners.forEach { listener ->
      try {
        listener.stateChanged(newState, previous)
      } catch (e: Throwable) {
        exceptionList.value += e
      }
    }
  }

  // Template-method close: idempotency is enforced here so subclasses cannot
  // forget to guard against double-close. Subclasses override doClose() for
  // their cleanup. @Synchronized preserves mutual exclusion with other
  // @Synchronized methods on the same instance (matches prior contract).
  @Synchronized
  final override fun close() {
    if (!closeCalled.compareAndSet(false, true)) return
    doClose()
  }

  protected open fun doClose() {}

  companion object {
    internal const val TOKEN_LENGTH = 7
    internal const val DEFAULT_TTL_SECS = 2L

    internal fun defaultClientId(prefix: String) = "$prefix:${randomId(TOKEN_LENGTH)}"
  }
}
