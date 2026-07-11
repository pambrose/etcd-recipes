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

import io.etcd.jetcd.Client
import io.etcd.jetcd.common.exception.ErrorCode
import io.etcd.jetcd.common.exception.EtcdException
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.support.CloseableClient
import io.etcd.jetcd.support.Observers
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/**
 * A keep-alive that survives lease expiry.
 *
 * jetcd auto-restarts the keep-alive stream after transient errors with observers
 * kept, so renewal resumes by itself; those surface as [LeaseEvent.Suspended] only.
 * What jetcd reports as *gone* — `onCompleted` (renewal stopped past the TTL) or a
 * NOT_FOUND "requested lease not found" — triggers healing: re-grant the lease,
 * re-run the establish hook to re-create the owned keys, and re-register the
 * keep-alive, paced by the configured [RetryPolicy].
 *
 * Threading: jetcd delivers lease-stream callbacks on its own scheduler; everything
 * here hops onto a dedicated single-thread executor so the blocking heal RPCs (and
 * listener callbacks) stay off jetcd's threads. Backoff delays are scheduled, never
 * slept, so [close] can cancel a pending attempt immediately.
 */
class SelfHealingKeepAlive internal constructor(
  private val client: Client,
  private val ttl: Duration,
  private val resilience: LeaseResilience,
  private val leaseListener: LeaseListener?,
  private val establish: (lease: LeaseGrantResponse) -> Boolean,
) : Closeable {
  private val healer: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "etcd-lease-healer").apply { isDaemon = true }
    }
  private val closed = AtomicBoolean(false)
  private val lock = Any() // guards registration + pendingAttempt against close() racing a heal

  @Volatile
  private var lease: LeaseGrantResponse? = null
  private var registration: CloseableClient? = null
  private var pendingAttempt: ScheduledFuture<*>? = null

  @Volatile
  private var healthy = true

  // Healer-thread-confined after the initial establish:
  private var healing = false
  private var attempt = 0
  private var healStart: TimeMark? = null
  private var lastCause: Throwable? = null

  val currentLeaseId: Long get() = lease?.id ?: -1L
  val isHealthy: Boolean get() = healthy && !closed.load()

  internal fun start() {
    val granted = client.leaseGrant(ttl)
    if (!establish(granted)) {
      client.leaseRevoke(granted)
      throw EtcdRecipeRuntimeException("Establish hook declined initial lease ${granted.id}")
    }
    lease = granted
    synchronized(lock) { registration = register(granted) }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(lock) {
      pendingAttempt?.cancel(false)
      registration?.close()
      registration = null
    }
    healer.shutdown()
    try {
      if (!healer.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warn { "Lease healer did not terminate within 5 seconds; a heal attempt may still be running" }
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    lease?.let { client.leaseRevoke(it) }
  }

  private fun register(granted: LeaseGrantResponse): CloseableClient =
    client.leaseClient.keepAlive(
      granted.id,
      Observers.builder<LeaseKeepAliveResponse>()
        .onNext { next -> logger.debug { "KeepAlive next resp: $next" } }
        .onError { e -> onStreamEvent(granted, e) }
        .onCompleted { onStreamEvent(granted, null) }
        .build(),
    )

  // jetcd semantics: onCompleted = the lease outlived its TTL unrenewed (DeadLine
  // service); onError(NOT_FOUND "requested lease not found") = etcd reports the
  // lease gone. Both mean expired -> heal. Any other onError is transient: jetcd
  // restarts the stream itself with this observer still registered.
  private fun onStreamEvent(
    granted: LeaseGrantResponse,
    error: Throwable?,
  ) {
    if (closed.load()) return
    if (granted.id != lease?.id) return // stale generation
    val expired = error == null || error.isLeaseNotFound()
    dispatch {
      if (expired) {
        startHeal(granted.id, error)
      } else {
        emit(LeaseEvent.Suspended(granted.id, error))
      }
    }
  }

  private fun dispatch(task: () -> Unit) {
    try {
      healer.execute {
        if (closed.load()) return@execute
        runCatching(task).onFailure { e -> logger.error(e) { "Lease healer task threw" } }
      }
    } catch (_: RejectedExecutionException) {
      // closed concurrently; nothing left to heal
    }
  }

  private fun startHeal(
    expiredLeaseId: Long,
    cause: Throwable?,
  ) {
    if (healing) return
    healing = true
    healthy = false
    lastCause = cause
    logger.warn(cause) { "Lease $expiredLeaseId expired; attempting to re-grant and re-establish" }
    emit(LeaseEvent.Expired(expiredLeaseId, cause))
    attempt = 0
    healStart = TimeSource.Monotonic.markNow()
    scheduleNextAttempt(expiredLeaseId)
  }

  private fun scheduleNextAttempt(expiredLeaseId: Long) {
    attempt += 1
    val elapsed = healStart?.elapsedNow() ?: Duration.ZERO
    val delay = resilience.retryPolicy.nextDelay(attempt, elapsed)
    if (delay == null) {
      logger.error(lastCause) { "Abandoning lease heal: retry policy exhausted after ${attempt - 1} attempts" }
      emit(LeaseEvent.Failed(expiredLeaseId, lastCause))
      return
    }
    synchronized(lock) {
      if (closed.load()) return
      pendingAttempt = healer.schedule({ runAttempt(expiredLeaseId) }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
  }

  @Suppress("TooGenericExceptionCaught", "ReturnCount")
  private fun runAttempt(expiredLeaseId: Long) {
    if (closed.load()) return
    try {
      val granted =
        client.leaseClient
          .grant(
            ttl.toDouble(DurationUnit.SECONDS).toLong(),
            resilience.healOperationTimeout.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
          ).get()
      if (!runEstablishForHeal(granted, expiredLeaseId)) return
      synchronized(lock) {
        if (closed.load()) {
          client.leaseRevoke(granted)
          return
        }
        registration?.close()
        registration = register(granted)
      }
      lease = granted
      healing = false
      healthy = true
      logger.info { "Lease healed: $expiredLeaseId -> ${granted.id} (attempt $attempt)" }
      emit(LeaseEvent.Restored(expiredLeaseId, granted.id))
    } catch (e: Throwable) {
      lastCause = e
      scheduleNextAttempt(expiredLeaseId)
    }
  }

  // Returns false (after emitting Failed) when the establish hook declines the new
  // lease — ownership is gone and must not be reclaimed.
  private fun runEstablishForHeal(
    granted: LeaseGrantResponse,
    expiredLeaseId: Long,
  ): Boolean {
    if (establish(granted)) return true
    client.leaseRevoke(granted)
    logger.warn { "Establish hook declined healed lease ${granted.id}; abandoning heal of $expiredLeaseId" }
    emit(LeaseEvent.Failed(expiredLeaseId, lastCause))
    return false
  }

  private fun emit(event: LeaseEvent) {
    runCatching { leaseListener?.onLeaseEvent(event) }
      .onFailure { e -> logger.error(e) { "Lease listener threw on $event" } }
  }
}

/**
 * Grants a lease with [ttl], invokes [establish] to create the caller's keys bound
 * to it (synchronously — a `false` return or a throw aborts), keeps the lease
 * alive, and heals it on expiry: re-grant, re-run [establish] with the fresh lease,
 * re-register the keep-alive. On every heal, [establish] must put/CAS the keys it
 * owns under the new lease id and return `true` — or return `false` to signal that
 * ownership is gone and must not be reclaimed (emits [LeaseEvent.Failed]).
 */
fun Client.selfHealingKeepAlive(
  ttl: Duration,
  resilience: LeaseResilience = LeaseResilience.DEFAULT,
  leaseListener: LeaseListener? = null,
  establish: (lease: LeaseGrantResponse) -> Boolean,
): SelfHealingKeepAlive = SelfHealingKeepAlive(this, ttl, resilience, leaseListener, establish).also { it.start() }

/**
 * True when this failure (or any cause in its chain) is etcd's NOT_FOUND
 * "requested lease not found" — jetcd's signal that a lease is gone for good,
 * as opposed to a transient stream error it retries itself.
 */
internal fun Throwable.isLeaseNotFound(): Boolean =
  generateSequence(this) { it.cause.takeIf { c -> c !== it } }
    .filterIsInstance<EtcdException>()
    .any { it.errorCode == ErrorCode.NOT_FOUND }
