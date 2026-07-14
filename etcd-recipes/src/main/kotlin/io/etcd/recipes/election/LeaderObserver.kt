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

import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchRecoveryListener
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.watcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs [receiver] with a started [LeaderObserver], closing it on exit.
 */
@JvmOverloads
fun <T> withLeaderObserver(
  client: Client,
  electionPath: String,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  receiver: LeaderObserver.() -> T,
): T = LeaderObserver(client, electionPath, resilience).use { it.start().receiver() }

/**
 * Watches an election **without being a candidate**: exposes the current leader's
 * clientId and notifies a [LeaderListener] on each hand-off. The blocking/Java
 * counterpart to the coroutine `Client.leadershipAsFlow`. Backed by the resilient
 * watcher, so it survives etcd restarts and compaction (a hand-off missed while the
 * stream was dead is recovered by re-reading the leader key).
 *
 * Listener callbacks fire on the watch dispatcher thread and must not block for long.
 */
class LeaderObserver
  @JvmOverloads
  constructor(
    client: Client,
    val electionPath: String,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : EtcdConnector(client, resilience) {
    init {
      require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    }

    private val leaderKey = ElectionPaths.leaderKey(electionPath)
    private val listeners = CopyOnWriteArrayList<LeaderListener>()
    private val currentLeaderRef = AtomicReference<String?>(null)

    @Volatile
    private var watcher: Watch.Watcher? = null

    /** The current leader's clientId, or null when the election is vacant. */
    val currentLeader: String? get() = currentLeaderRef.get()

    fun addListener(listener: LeaderListener) {
      listeners += listener
    }

    fun removeListener(listener: LeaderListener) {
      listeners -= listener
    }

    @Synchronized
    fun start(): LeaderObserver {
      checkCloseNotCalled()
      if (!startCalled.compareAndSet(false, true))
        throw EtcdRecipeRuntimeException("start() already called")
      // Seed the snapshot: a listener registered before start() reads currentLeader; only
      // CHANGES (PUT/DELETE) fire callbacks, so no synthetic take/relinquish is emitted.
      currentLeaderRef.set(readLeader())
      watcher =
        client.watcher(
          leaderKey,
          WatchOption.DEFAULT,
          resilience.watch,
          recoveryListener = WatchRecoveryListener { event -> onRecovery(event) },
          resyncWith = null,
        ) { response ->
          response.events.forEach { event ->
            when (event.eventType) {
              PUT -> setLeader(ElectionPaths.stripLeaderClientId(event.keyValue.value.asString))
              DELETE -> clearLeader()
              else -> logger.error { "Unrecognized event on $leaderKey" }
            }
          }
        }
      startThreadComplete.set(true)
      return this
    }

    private fun readLeader(): String? =
      client.getValue(leaderKey, resilience.rpc)?.asString?.let { ElectionPaths.stripLeaderClientId(it) }

    @Suppress("TooGenericExceptionCaught")
    private fun setLeader(name: String) {
      currentLeaderRef.set(name)
      listeners.forEach { listener ->
        try {
          listener.takeLeadership(name)
        } catch (e: Throwable) {
          logger.error(e) { "Exception in LeaderObserver takeLeadership" }
          runCatching { listener.onError(e) }
          exceptionList.value += e
        }
      }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun clearLeader() {
      currentLeaderRef.set(null)
      listeners.forEach { listener ->
        try {
          listener.relinquishLeadership()
        } catch (e: Throwable) {
          logger.error(e) { "Exception in LeaderObserver relinquishLeadership" }
          runCatching { listener.onError(e) }
          exceptionList.value += e
        }
      }
    }

    // A hand-off can be lost while the stream is fatally dead; re-read the leader key
    // after recovery and replay the current state. Mirrors LeaderSelector.reportLeader's
    // recovery listener.
    private fun onRecovery(event: WatchRecoveryEvent) {
      reportRecoveryEvent(event)
      when (event) {
        is WatchRecoveryEvent.Resubscribed, is WatchRecoveryEvent.Resynced -> {
          val leader = readLeader()
          if (leader != null) setLeader(leader) else clearLeader()
        }

        is WatchRecoveryEvent.Failed -> {
          listeners.forEach { listener ->
            runCatching {
              listener.onError(event.cause ?: EtcdRecipeRuntimeException("Leader watch on $electionPath abandoned"))
            }
          }
        }

        is WatchRecoveryEvent.Suspended -> {
          Unit
        }
      }
    }

    @Synchronized
    override fun doClose() {
      watcher?.close()
      watcher = null
      listeners.clear()
    }

    companion object {
      private val logger = KotlinLogging.logger {}
    }
  }
