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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch

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
        emptyList()

  val hasExceptions get() = exceptionList.isInitialized() && exceptionList.value.isNotEmpty()

  private val droppedExceptions = AtomicLong(0L)

  /**
   * How many recorded exceptions were dropped from [exceptions], which keeps only the most
   * recent [MAX_RECORDED_EXCEPTIONS] so a long-lived recipe's list can't grow without bound.
   */
  val droppedExceptionCount: Long get() = droppedExceptions.load()

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
   * The single sink for background failures: records [throwable] in [exceptions] (keeping the
   * most recent [MAX_RECORDED_EXCEPTIONS]) and pushes it to every [BackgroundExceptionListener]
   * with a short source [context], on this connector's notifier thread. Recipes call this
   * instead of appending to the exception list directly. It never blocks, so it is safe from
   * any thread, including jetcd's. A listener that throws is logged and dropped — never
   * re-recorded — so notification cannot recurse.
   */
  @Suppress("TooGenericExceptionCaught")
  protected fun recordException(
    context: String,
    throwable: Throwable,
  ) {
    val list = exceptionList.value
    synchronized(list) {
      if (list.size >= MAX_RECORDED_EXCEPTIONS) {
        list.removeAt(0)
        droppedExceptions.incrementAndFetch()
      }
      list += throwable
    }
    // The listeners registered when it was reported get it, even one removed before delivery
    val targets = backgroundExceptionListeners.toList()
    if (targets.isEmpty()) return
    notifyAsync {
      targets.forEach { listener ->
        try {
          listener.onException(context, throwable)
        } catch (e: Throwable) {
          logger.error(e) { "Background-exception listener threw while handling [$context]" }
        }
      }
    }
  }

  // Listener callbacks run here, one at a time, in the order they were reported: never on the
  // reporting thread, which can be jetcd's event loop (lease keep-alive callbacks), where a
  // listener that blocks or makes an RPC would stall every lease and watch on the client.
  private val notifierDelegate =
    lazy {
      Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "etcd-recipe-notifier").apply { isDaemon = true }
      }
    }

  /**
   * Runs [task] on this connector's notifier thread, after everything queued before it. Recipes
   * use it for user callbacks fired from a thread that must not block (a lost lock's listeners
   * and interrupt run from jetcd's lease callback). A task queued after [close] is dropped.
   */
  protected fun notifyAsync(task: () -> Unit) {
    try {
      notifierDelegate.value.execute { withRecipeLoggingContext(task) }
    } catch (e: RejectedExecutionException) {
      logger.debug(e) { "Notification dropped: $exceptionContext is closed" }
    }
  }

  protected fun checkStartCalled() {
    if (!startCalled.load()) throw EtcdRecipeRuntimeException("start() not called")
  }

  private val connectionStateRef = AtomicReference(ConnectionState.CONNECTED)
  private val connectionStateListeners = CopyOnWriteArrayList<ConnectionStateListener>()

  // Serializes a state change with queuing its notification, so listeners see changes in the
  // order they happened. Also guards lostForGood.
  private val transitionLock = Any()

  // Set by a LOST from a stream that is gone for good (watch recovery or lease healing
  // abandoned, or a failed start). Until the recipe restarts, other streams' events can't
  // clear it: a later RECONNECTED from a healthy stream would otherwise mask a dead one.
  private var lostForGood = false

  /** Whether this connector reported a LOST that nothing but a restart clears. */
  internal val isLostForGood: Boolean get() = synchronized(transitionLock) { lostForGood }

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
      is WatchRecoveryEvent.Failed -> transitionTo(ConnectionState.LOST, forGood = true)
    }
  }

  /**
   * Returns [connectionState] to [ConnectionState.CONNECTED] when a reusable recipe starts a
   * new cycle, so it does not report the previous cycle's LOST (even one that was otherwise
   * permanent). Listeners see the transition.
   */
  protected fun resetConnectionState() {
    synchronized(transitionLock) {
      lostForGood = false
      transitionTo(ConnectionState.CONNECTED)
    }
  }

  /**
   * Reports [ConnectionState.LOST] for a recipe that can no longer track etcd for a reason
   * no watch or lease event carries, such as a start that failed before its watch existed.
   * Like an abandoned stream, it lasts until the recipe restarts.
   */
  protected fun reportConnectionLost() = transitionTo(ConnectionState.LOST, forGood = true)

  /**
   * For a recipe built from other recipes: mirrors [inner]'s recorded failures and connection
   * state into this connector, including a LOST that is permanent. Returns the handle that
   * stops forwarding (call it when [inner] is retired).
   */
  protected fun forwardHealthOf(inner: EtcdConnector): () -> Unit {
    val exceptionListener = BackgroundExceptionListener { context, throwable -> recordException(context, throwable) }
    val stateListener =
      ConnectionStateListener { newState, _ ->
        transitionTo(newState, forGood = newState == ConnectionState.LOST && inner.isLostForGood)
      }
    inner.addBackgroundExceptionListener(exceptionListener)
    inner.addConnectionStateListener(stateListener)
    return {
      inner.removeBackgroundExceptionListener(exceptionListener)
      inner.removeConnectionStateListener(stateListener)
    }
  }

  /** Recipes feed their lease events here to drive [connectionState]. */
  protected fun reportLeaseEvent(event: LeaseEvent) {
    when (event) {
      is LeaseEvent.Suspended -> transitionTo(ConnectionState.SUSPENDED)
      is LeaseEvent.Expired -> transitionTo(ConnectionState.LOST)
      is LeaseEvent.Restored -> transitionTo(ConnectionState.RECONNECTED)
      is LeaseEvent.Failed -> transitionTo(ConnectionState.LOST, forGood = true)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun transitionTo(
    newState: ConnectionState,
    forGood: Boolean = false,
  ) {
    // Change and queue the notification under one lock, so listeners (on the notifier thread)
    // see changes in the order they happened. Equal states are dropped, so repeated Suspended
    // reports during one outage notify once. A permanent LOST blocks every other change.
    synchronized(transitionLock) {
      if (lostForGood) return
      if (forGood) lostForGood = true
      val previous = connectionStateRef.exchange(newState)
      // The listeners registered when the state changed get it, even one removed before delivery
      val targets = connectionStateListeners.toList()
      if (previous != newState && targets.isNotEmpty()) {
        notifyAsync {
          targets.forEach { listener ->
            try {
              listener.stateChanged(newState, previous)
            } catch (e: Throwable) {
              recordException("connection-state-listener", e)
            }
          }
        }
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
  fun ping(): Boolean = ping(RpcResilience.PROBE.withMetrics(resilience.metrics))

  /** [ping] under [rpc] rather than the default single short attempt ([RpcResilience.PROBE]). */
  fun ping(rpc: RpcResilience): Boolean = client.ping(rpc)

  // Template-method close: idempotency is enforced here so subclasses cannot
  // forget to guard against double-close. Subclasses override doClose() for
  // their cleanup. @Synchronized preserves mutual exclusion with other
  // @Synchronized methods on the same instance (matches prior contract).
  @Synchronized
  final override fun close() {
    if (!closeCalled.compareAndSet(false, true)) return
    try {
      doClose()
    } finally {
      // Notifications already queued (including doClose()'s own) still run; later ones drop
      if (notifierDelegate.isInitialized()) notifierDelegate.value.shutdown()
    }
  }

  protected open fun doClose() {}

  companion object {
    private val logger = KotlinLogging.logger {}
    internal const val TOKEN_LENGTH = 7

    /** How many exceptions [exceptions] keeps; older ones count in [droppedExceptionCount]. */
    const val MAX_RECORDED_EXCEPTIONS = 100
    internal const val DEFAULT_TTL_SECS = 2L

    /** SLF4J MDC key under which [withRecipeLoggingContext] publishes the recipe's identity. */
    const val RECIPE_MDC_KEY = "etcd.recipe"

    internal fun defaultClientId(prefix: String) = "$prefix:${randomId(TOKEN_LENGTH)}"
  }
}
