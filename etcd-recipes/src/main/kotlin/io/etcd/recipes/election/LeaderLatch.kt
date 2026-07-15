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

package io.etcd.recipes.election

import com.pambrose.common.concurrent.BooleanMonitor
import com.pambrose.common.time.timeUnitToDuration
import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.DEFAULT_TTL_SECS
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [receiver] with a started [LeaderLatch], closing it (releasing candidacy) on exit.
 */
@JvmOverloads
fun <T> withLeaderLatch(
  client: Client,
  electionPath: String,
  leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  clientId: String = LeaderLatch.defaultClientId(),
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  receiver: LeaderLatch.() -> T,
): T = LeaderLatch(client, electionPath, leaseTtlSecs, clientId, resilience).use { it.start().receiver() }

/**
 * A Curator-style leader latch: acquire leadership and **hold it until [close]**, unlike
 * [LeaderSelector] whose leadership lives only inside a callback. Query [hasLeadership],
 * block on [await], or register a [LeaderLatchListener].
 *
 * Internally a worker thread runs a term loop, composing a fresh [LeaderSelector] per
 * leadership term (the selector is one-shot per term), so latches and selectors
 * interoperate in the same election. If the leadership lease is lost (partition past the
 * TTL) the latch steps down, fires [LeaderLatchListener.notLeader], and re-contests.
 *
 * One-shot: [start] and [close] may each be called once (unlike the reusable
 * [LeaderSelector]). A parked [await] that never gained leadership is released only by
 * interruption or its timeout — [close] does not unblock it; prefer the timed [await].
 */
class LeaderLatch
  @JvmOverloads
  constructor(
    client: Client,
    val electionPath: String,
    val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
    val clientId: String = defaultClientId(),
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
    private val interruptOnLeaseLoss: Boolean = true,
    private val closeJoinTimeout: Duration = 30.seconds,
  ) : EtcdConnector(client, resilience) {
    init {
      require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
      require(leaseTtlSecs > 0) { "Lease TTL must be > 0" }
    }

    override val exceptionContext get() = "LeaderLatch[$electionPath]"

    private val closing = AtomicBoolean(false)

    // Serializes inner-selector lifecycle (construct/start) against close(): the worker
    // holds it across construct+start; doClose() holds it to flip `closing` and close the
    // current selector. Never the instance monitor (close() is @Synchronized), so a
    // close() during leadership cannot deadlock. The worker never takes the instance
    // monitor, so there is no lock inversion.
    private val stateLock = Any()
    private val currentSelector = AtomicReference<LeaderSelector?>(null)
    private val leadershipMonitor = BooleanMonitor(false)
    private val firstTermStarted = BooleanMonitor(false)
    private val listeners = CopyOnWriteArrayList<LeaderLatchListener>()
    private val notifyExecutor =
      Executors.newSingleThreadExecutor { r ->
        Thread(r, "leader-latch-notify-$clientId").apply { isDaemon = true }
      }

    @Volatile
    private var worker: Thread? = null

    val hasLeadership: Boolean get() = leadershipMonitor.get()

    fun addListener(listener: LeaderLatchListener) {
      listeners += listener
    }

    fun removeListener(listener: LeaderLatchListener) {
      listeners -= listener
    }

    fun start(): LeaderLatch {
      checkCloseNotCalled()
      if (!startCalled.compareAndSet(false, true))
        throw EtcdRecipeRuntimeException("start() already called")
      worker =
        thread(start = true, isDaemon = true, name = "leader-latch-$clientId") {
          withRecipeLoggingContext { runTermLoop() }
        }
      // Block until the first candidacy is registered (or the worker exited early).
      firstTermStarted.waitUntilTrue()
      return this
    }

    @Throws(InterruptedException::class)
    fun await() {
      checkStartCalled()
      leadershipMonitor.waitUntilTrueWithInterruption()
    }

    @Throws(InterruptedException::class)
    fun await(timeout: Duration): Boolean {
      checkStartCalled()
      return leadershipMonitor.waitUntilTrueWithInterruption(timeout)
    }

    @Throws(InterruptedException::class)
    fun await(
      timeout: Long,
      timeUnit: TimeUnit,
    ): Boolean = await(timeUnitToDuration(timeout, timeUnit))

    @Suppress("TooGenericExceptionCaught")
    private fun runTermLoop() {
      try {
        while (true) {
          val sel =
            synchronized(stateLock) {
              if (closing.get()) return
              LeaderSelector(
                client,
                electionPath,
                takeLeadershipBlock = { selector ->
                  onBecameLeader()
                  // Hold leadership until this term ends (close or step-down). Park on
                  // waitUntilFinished, NOT waitOnLeadershipComplete — the latter's
                  // checkCloseNotCalled() would throw once close() runs.
                  selector.waitUntilFinished()
                },
                relinquishLeadershipBlock = { onLostLeadership() },
                leaseTtlSecs = leaseTtlSecs,
                executorService = null,
                clientId = clientId,
                resilience = resilience,
                interruptOnLeaseLoss = interruptOnLeaseLoss,
              ).also {
                currentSelector.set(it)
                it.start()
              }
            }

          firstTermStarted.set(true)

          try {
            sel.waitUntilFinished()
          } catch (e: InterruptedException) {
            // close() interrupted us as a backstop; the loop guard below handles it.
            logger.debug(e) { "Latch worker interrupted while awaiting term end" }
          }

          if (sel.hasExceptions) sel.exceptions.forEach { recordException(it) }
          runCatching { sel.close() }.onFailure { recordException(it) }

          if (closing.get()) return
          // Otherwise the term ended via step-down: loop back and re-contest with a
          // fresh selector (one selector per full term, so no tight spin).
        }
      } catch (e: Throwable) {
        recordException(e)
      } finally {
        leadershipMonitor.set(false)
        firstTermStarted.set(true) // never leave start() blocked if the worker died early
        startThreadComplete.set(true)
      }
    }

    internal fun onBecameLeader() {
      leadershipMonitor.set(true)
      notifyListeners { isLeader() }
    }

    internal fun onLostLeadership() {
      leadershipMonitor.set(false)
      notifyListeners { notLeader() }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun notifyListeners(crossinline call: LeaderLatchListener.() -> Unit) {
      val snapshot = listeners.toList()
      try {
        notifyExecutor.execute {
          withRecipeLoggingContext {
            snapshot.forEach { listener ->
              try {
                listener.call()
              } catch (e: Throwable) {
                logger.error(e) { "Exception in LeaderLatch listener" }
                recordException(e)
              }
            }
          }
        }
      } catch (e: RejectedExecutionException) {
        // notifyExecutor already shut down during close(); drop the notification
        logger.debug(e) { "Dropping listener notification after close()" }
      }
    }

    override fun doClose() {
      synchronized(stateLock) {
        closing.set(true)
        currentSelector.get()?.let { runCatching { it.close() } } // unblocks the worker's park
      }
      worker?.let { w ->
        w.join(closeJoinTimeout.inWholeMilliseconds)
        if (w.isAlive) {
          w.interrupt() // backstop
          w.join(TimeUnit.SECONDS.toMillis(5))
          if (w.isAlive)
            recordException(EtcdRecipeRuntimeException("LeaderLatch worker did not terminate"))
        }
      }
      leadershipMonitor.set(false)
      notifyExecutor.shutdown() // graceful: a queued notLeader still runs
      runCatching { notifyExecutor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    companion object {
      private val logger = KotlinLogging.logger {}

      fun defaultClientId(): String = EtcdConnector.defaultClientId(LeaderLatch::class.simpleName!!)
    }
  }
