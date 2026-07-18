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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
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
  protected val exceptionList: Lazy<MutableList<Throwable>> = lazy { synchronizedList([]) }

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
        []

  val hasExceptions get() = exceptionList.isInitialized() && exceptionList.value.isNotEmpty()

  fun clearExceptions() {
    if (exceptionList.isInitialized()) exceptionList.value.clear()
  }

  private val backgroundExceptionListeners = CopyOnWriteArrayList<BackgroundExceptionListener>()

  fun addBackgroundExceptionListener(listener: BackgroundExceptionListener) {
    backgroundExceptionListeners += listener
  }

  fun removeBackgroundExceptionListener(listener: BackgroundExceptionListener) {
    backgroundExceptionListeners -= listener
  }

  /**
   * Short source hint attached to background exceptions from this connector so a shared
   * handler can attribute which recipe failed. Subclasses override it with their path /
   * clientId; the default is the recipe type name.
   */
  protected open val exceptionContext: String
    get() = this::class.simpleName ?: "EtcdConnector"

  /** Records [throwable] under this connector's [exceptionContext]. */
  protected fun recordException(throwable: Throwable) = recordException(exceptionContext, throwable)

  /**
   * Runs [body] with this recipe's identity ([exceptionContext]) in the SLF4J MDC under
   * [RECIPE_MDC_KEY], restoring any prior value afterward. Recipes wrap their background-thread
   * runnables with this so logs emitted far from the calling code still carry recipe context.
   */
  protected inline fun <T> withRecipeLoggingContext(body: () -> T): T {
    val prior = MDC.get(RECIPE_MDC_KEY)
    MDC.put(RECIPE_MDC_KEY, exceptionContext)
    return try {
      body()
    } finally {
      if (prior == null) MDC.remove(RECIPE_MDC_KEY) else MDC.put(RECIPE_MDC_KEY, prior)
    }
  }

  /**
   * The single sink for background failures: records [throwable] in [exceptions] and pushes
   * it to every [BackgroundExceptionListener] with a short source [context]. Recipes call
   * this instead of appending to the exception list directly. A listener that throws is
   * logged and dropped — never re-recorded — so notification cannot recurse.
   */
  @Suppress("TooGenericExceptionCaught")
  protected fun recordException(
    context: String,
    throwable: Throwable,
  ) {
    exceptionList.value += throwable
    backgroundExceptionListeners.forEach { listener ->
      try {
        listener.onException(context, throwable)
      } catch (e: Throwable) {
        logger.error(e) { "Background-exception listener threw while handling [$context]" }
      }
    }
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
        recordException("connection-state-listener", e)
      }
    }
  }

  /**
   * Passive health: healthy unless a lease expired or a watcher was abandoned
   * ([connectionState] == [ConnectionState.LOST]), or this connector is closed. Derived from
   * events the recipes already observe — no RPC. For an active check use [ping].
   */
  fun isHealthy(): Boolean = connectionState != ConnectionState.LOST && !closeCalled.load()

  /**
   * Active reachability probe: a bounded, non-mutating count-only GET against etcd, through
   * the same retry/timeout funnel as every other RPC. Returns false instead of throwing when
   * etcd cannot be reached within the RPC timeout.
   */
  fun ping(): Boolean =
    runCatching { client.getResponse(PING_PROBE_KEY, getOption { withCountOnly(true) }, resilience.rpc) }.isSuccess

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
    private val logger = KotlinLogging.logger {}
    internal const val TOKEN_LENGTH = 7
    internal const val DEFAULT_TTL_SECS = 2L
    private const val PING_PROBE_KEY = "health-check-probe"

    /** SLF4J MDC key under which [withRecipeLoggingContext] publishes the recipe's identity. */
    const val RECIPE_MDC_KEY = "etcd.recipe"

    internal fun defaultClientId(prefix: String) = "$prefix:${randomId(TOKEN_LENGTH)}"
  }
}
