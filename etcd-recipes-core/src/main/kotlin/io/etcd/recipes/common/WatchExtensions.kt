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

@file:JvmName("WatchUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.common.exception.ClosedClientException
import io.etcd.jetcd.common.exception.CompactedException
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.jetcd.watch.WatchResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.slf4j.MDC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.math.max
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

val WatchEvent.keyAsString get() = keyValue.key.asString
val WatchEvent.keyAsInt get() = keyValue.key.asInt
val WatchEvent.keyAsLong get() = keyValue.key.asLong

val WatchEvent.valueAsString get() = keyValue.value.asString
val WatchEvent.valueAsInt get() = keyValue.value.asInt
val WatchEvent.valueAsLong get() = keyValue.value.asLong

@JvmOverloads
fun Client.watcher(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  block: (WatchResponse) -> Unit,
): Watch.Watcher = watcher(keyName, option, WatchResilience.DEFAULT, null, null, block)

/**
 * Creates a watcher that survives fatal watch-stream deaths.
 *
 * jetcd transparently retries *transient* stream errors itself; what it abandons — and
 * this watcher recovers per [resilience] — are *fatal* deaths (compaction of the watched
 * revision, halt-error statuses, "etcdserver: no leader"). Recovery re-subscribes from the
 * revision just past the last observed event, or, before any event, from where the watch
 * started (for an un-anchored watch, the revision at which it was created), so events
 * committed during a recovery are replayed rather than lost. The exception is
 * compaction: there [resyncWith] is invoked so the caller can re-read its world (GET +
 * rebuild derived state) and return the new anchor revision (typically the GET's
 * `header.revision + 1`); a failed resync is retried. Without [resyncWith] the watch
 * resumes at the compacted revision (the oldest etcd still serves) and the gap is
 * reported via [WatchRecoveryEvent.Resynced] — callers with derived state should supply
 * [resyncWith]. Closing the [Client] ends recovery with [WatchRecoveryEvent.Failed].
 */
fun Client.watcher(
  keyName: String,
  option: WatchOption,
  resilience: WatchResilience,
  recoveryListener: WatchRecoveryListener? = null,
  resyncWith: (() -> Long)? = null,
  block: (WatchResponse) -> Unit,
): Watch.Watcher =
  ResilientWatcher(this, keyName, option, resilience, recoveryListener, resyncWith, block)
    .also { it.subscribeInitial() }

/**
 * A [Watch.Watcher] wrapping ephemeral jetcd watchers, re-subscribing across fatal
 * stream deaths.
 *
 * Threading: jetcd 0.7+ delivers watch callbacks on its Vert.x gRPC event loop.
 * Anything a callback does that needs another gRPC response — or contends with a lock
 * the caller is holding while waiting on a gRPC response — would deadlock the event
 * loop. Every callback therefore hops onto a dedicated single-thread scheduled
 * executor; the recovery loop (including the blocking resync GET) runs on that same
 * executor, and backoff delays are scheduled rather than slept so close() can cancel
 * a pending attempt immediately.
 */
private class ResilientWatcher(
  private val client: Client,
  private val keyName: String,
  private val baseOption: WatchOption,
  private val resilience: WatchResilience,
  private val recoveryListener: WatchRecoveryListener?,
  private val resyncWith: (() -> Long)?,
  private val block: (WatchResponse) -> Unit,
) : Watch.Watcher {
  // The dispatcher's thread, so close() from a callback running on it doesn't wait on itself
  @Volatile
  private var dispatcherThread: Thread? = null

  // The creator's logging context, applied to every callback and recovery attempt
  private val mdc: Map<String, String>? = MDC.getCopyOfContextMap()

  private val dispatcher: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "etcd-watch-dispatcher").apply {
        isDaemon = true
        dispatcherThread = this
      }
    }
  private val closed = AtomicBoolean(false)
  private val lock = Any() // guards delegate + pendingAttempt against close() racing recovery
  private var delegate: Watch.Watcher? = null
  private var pendingAttempt: ScheduledFuture<*>? = null

  // Dispatcher-thread-confined after construction:
  private var resumeRevision = baseOption.revision // 0 = "current revision"
  private var lastCause: Throwable? = null
  private var suspendedReported = false
  private var attempt = 0
  private var recoveryStart: TimeMark? = null

  // A compaction that still needs a successful resync; kept across failed attempts
  private var pendingCompactRevision: Long? = null

  // Whether the stream from the last recovery delivered anything. Until it does, a new
  // death continues that recovery's retry budget instead of starting a fresh one.
  private var recoveryConfirmed = true

  private val listener =
    object : Watch.Listener {
      override fun onNext(response: WatchResponse) {
        dispatch {
          if (response.isCreatedNotify) {
            // Its header is the store's current revision, not where the watch starts: only an
            // un-anchored watch that has seen nothing takes it as its resume point.
            if (resumeRevision == 0L) resumeRevision = response.header.revision + 1
          } else {
            advanceRevision(response)
          }
          recoveryConfirmed = true
          // A response after a transient error means jetcd recovered the stream by itself;
          // report it, or listeners (and connectionState) would stay SUSPENDED for good.
          if (suspendedReported) {
            suspendedReported = false
            emit(WatchRecoveryEvent.Resubscribed(keyName, resumeRevision))
          }
          // The created notification is always requested internally; pass it on only if asked
          if (response.isCreatedNotify && !baseOption.isCreatedNotify) return@dispatch
          runCatching { block(response) }
            .onFailure { e -> logger.error(e) { "Watch block for $keyName threw" } }
        }
      }

      override fun onError(throwable: Throwable) {
        dispatch {
          lastCause = throwable
          if (!suspendedReported) {
            suspendedReported = true
            emit(WatchRecoveryEvent.Suspended(keyName, throwable))
          }
        }
      }

      override fun onCompleted() {
        // jetcd fires this when it abandons the watch as fatally dead — and also as a
        // side effect of our own close(); the closed flag inside dispatch() filters that.
        dispatch { startRecovery() }
      }
    }

  fun subscribeInitial() {
    val watcher = client.watchClient.watch(keyName.asByteSequence, buildOption(), listener)
    synchronized(lock) { delegate = watcher }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(lock) {
      pendingAttempt?.cancel(false)
      delegate?.close()
    }
    dispatcher.shutdown()
    // close() from a callback on the dispatcher (a cache listener closing its cache) can't
    // wait for itself to finish; the callback completes once it returns.
    if (Thread.currentThread() === dispatcherThread) return
    // shutdown() refuses new tasks but does not wait for the currently-running
    // callback task. Without awaiting, the user's `block` could still be
    // executing on the dispatcher thread after withWatcher's receiver has
    // returned — touching state the caller assumes is no longer in use. A
    // short bounded wait makes the close-then-touch contract real without
    // turning into a blocker on a misbehaving callback.
    try {
      if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
        // The callback outran the bounded wait, so the close-then-touch contract
        // cannot be honored — surface it rather than pretending the dispatcher drained.
        logger.warn { "Watch dispatcher did not terminate within 5 seconds; a callback may still be running" }
      }
    } catch (e: InterruptedException) {
      // awaitTermination clears the interrupt flag on InterruptedException; restore it
      // so callers up the stack can still observe the interruption.
      Thread.currentThread().interrupt()
    }
  }

  override fun isClosed(): Boolean = closed.load()

  override fun requestProgress() {
    synchronized(lock) { delegate }?.requestProgress()
  }

  private fun dispatch(task: () -> Unit) {
    if (closed.load()) return
    try {
      dispatcher.execute {
        if (closed.load()) return@execute
        withMdc(mdc) {
          runCatching(task).onFailure { e -> logger.error(e) { "Watch dispatch for $keyName threw" } }
        }
      }
    } catch (_: RejectedExecutionException) {
      // closed concurrently; nothing left to deliver to
    }
  }

  private fun advanceRevision(response: WatchResponse) {
    val events = response.events
    resumeRevision =
      if (events.isNotEmpty()) {
        max(resumeRevision, events.maxOf { it.keyValue.modRevision } + 1)
      } else {
        // progress notification: synced through header.revision
        max(resumeRevision, response.header.revision + 1)
      }
  }

  private fun buildOption(): WatchOption =
    watchOption {
      if (resumeRevision > 0L) withRevision(resumeRevision)
      if (baseOption.isPrefix) isPrefix(true)
      baseOption.endKey.ifPresent { withRange(it) }
      withPrevKV(baseOption.isPrevKV)
      withProgressNotify(baseOption.isProgressNotify || resilience.progressNotify)
      // Always: it confirms each subscription is live and anchors an un-anchored watch
      withCreateNotify(true)
      withNoPut(baseOption.isNoPut)
      withNoDelete(baseOption.isNoDelete)
      withRequireLeader(baseOption.withRequireLeader())
    }

  private fun startRecovery() {
    if (!suspendedReported) {
      suspendedReported = true
      emit(
        WatchRecoveryEvent.Suspended(
          keyName,
          lastCause ?: EtcdRecipeRuntimeException("Watch stream for $keyName completed unexpectedly"),
        ),
      )
    }
    // A new stream that died before delivering anything didn't recover: keep that budget
    if (recoveryConfirmed) {
      attempt = 0
      recoveryStart = TimeSource.Monotonic.markNow()
      recoveryConfirmed = false
    }
    scheduleNextAttempt()
  }

  private fun scheduleNextAttempt() {
    attempt += 1
    val elapsed = recoveryStart?.elapsedNow() ?: kotlin.time.Duration.ZERO
    val delay = resilience.retryPolicy.nextDelay(attempt, elapsed)
    if (delay == null) {
      logger.error(lastCause) { "Abandoning watch on $keyName: retry policy exhausted after ${attempt - 1} attempts" }
      emit(WatchRecoveryEvent.Failed(keyName, lastCause))
      return
    }
    synchronized(lock) {
      if (closed.load()) return
      pendingAttempt =
        dispatcher.schedule({ withMdc(mdc) { runAttempt() } }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun runAttempt() {
    if (closed.load()) return
    try {
      // The marker survives a failed resync, so the next attempt resyncs instead of skipping it
      val compactRevision =
        pendingCompactRevision ?: compactedRevisionOf(lastCause)?.also { pendingCompactRevision = it }
      if (compactRevision != null) {
        // Events between compactRevision and the new anchor are unrecoverable; the
        // resync hook re-reads the world so derived state converges despite the gap.
        // compactRevision itself is the oldest revision etcd still serves.
        resumeRevision = resyncWith?.invoke() ?: compactRevision
      }
      val watcher = client.watchClient.watch(keyName.asByteSequence, buildOption(), listener)
      synchronized(lock) {
        if (closed.load()) {
          watcher.close()
          return
        }
        delegate = watcher
      }
      lastCause = null
      pendingCompactRevision = null
      suspendedReported = false
      emit(
        if (compactRevision != null) {
          WatchRecoveryEvent.Resynced(keyName, compactRevision, resumeRevision)
        } else {
          WatchRecoveryEvent.Resubscribed(keyName, resumeRevision)
        },
      )
      logger.info { "Watch on $keyName re-established (attempt $attempt, resume revision $resumeRevision)" }
    } catch (e: Throwable) {
      lastCause = e
      if (isClientClosed(e)) {
        // Retrying can't help: the client is gone. End recovery rather than retry forever.
        logger.warn(e) { "Abandoning watch on $keyName: its client is closed" }
        emit(WatchRecoveryEvent.Failed(keyName, e))
      } else {
        logger.debug(e) { "Watch recovery attempt $attempt on $keyName failed" }
        scheduleNextAttempt()
      }
    }
  }

  private fun isClientClosed(cause: Throwable): Boolean =
    generateSequence(cause) { it.cause.takeIf { c -> c !== it } }
      .any { it is ClosedClientException || (it is StatusRuntimeException && it.status.code == Status.Code.CANCELLED) }

  private fun compactedRevisionOf(cause: Throwable?): Long? =
    generateSequence(cause) { it.cause.takeIf { c -> c !== it } }
      .filterIsInstance<CompactedException>()
      .firstOrNull()
      ?.compactedRevision

  private fun emit(event: WatchRecoveryEvent) {
    resilience.metrics.incrementWatchRecovery(recoveryKind(event), keyName)
    runCatching { recoveryListener?.onRecoveryEvent(event) }
      .onFailure { e -> logger.error(e) { "Watch recovery listener for $keyName threw on $event" } }
  }

  private fun recoveryKind(event: WatchRecoveryEvent): String =
    when (event) {
      is WatchRecoveryEvent.Suspended -> "suspended"
      is WatchRecoveryEvent.Resubscribed -> "resubscribed"
      is WatchRecoveryEvent.Resynced -> "resynced"
      is WatchRecoveryEvent.Failed -> "failed"
    }
}

@JvmOverloads
fun <T> Client.withWatcher(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  block: (WatchResponse) -> Unit,
  receiver: Watch.Watcher.() -> T,
): T = withWatcher(keyName, option, WatchResilience.DEFAULT, null, null, block, receiver)

fun <T> Client.withWatcher(
  keyName: String,
  option: WatchOption,
  resilience: WatchResilience,
  recoveryListener: WatchRecoveryListener? = null,
  resyncWith: (() -> Long)? = null,
  block: (WatchResponse) -> Unit,
  receiver: Watch.Watcher.() -> T,
): T =
  watcher(keyName, option, resilience, recoveryListener, resyncWith, block)
    .use { watcher ->
      watcher.receiver()
    }

@JvmOverloads
fun Client.watcherWithLatch(
  keyName: String,
  endWatchLatch: CountDownLatch,
  onPut: (WatchEvent) -> Unit,
  onDelete: (WatchEvent) -> Unit,
  option: WatchOption = WatchOption.DEFAULT,
) {
  withWatcher(
    keyName,
    option,
    { watchResponse ->
      watchResponse.events
        .forEach { event ->
          when (event.eventType) {
            EventType.PUT -> {
              onPut(event)
            }

            EventType.DELETE -> {
              onDelete(event)
            }

            EventType.UNRECOGNIZED -> { // Ignore
            }

            else -> { // Ignore
            }
          }
        }
    },
  ) {
    endWatchLatch.await()
  }
}
