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

package io.etcd.recipes.election

import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle correctness of the election recipes:
 *
 * - a timed `waitOnLeadershipComplete` honors its timeout for a candidate that is not
 *   leading (it used to wait, untimed, for the candidacy to end);
 * - `close()` called from inside `takeLeadership` returns instead of deadlocking;
 * - a selector closed without ever winning can be started again, and a restart does not
 *   carry over the previous candidacy's connection state;
 * - `close()` on a selector that was never started does not throw;
 * - `LeaderObserver` replays the leader after a recovery only when events could have
 *   been missed, and reports a failed re-read to its listeners.
 */
class ElectionLifecycleTests : StringSpec() {
  private val base = "/election/${javaClass.simpleName}"

  private class RecordingListener : LeaderListener {
    val takes = CopyOnWriteArrayList<String>()
    val relinquishes = AtomicInt(0)
    val errors = CopyOnWriteArrayList<Throwable>()

    override fun takeLeadership(leaderName: String) {
      takes += leaderName
    }

    override fun relinquishLeadership() {
      relinquishes.incrementAndFetch()
    }

    override fun onError(e: Throwable) {
      errors += e
    }
  }

  private fun blockingLeader(release: CountDownLatch) =
    object : LeaderSelectorListenerAdapter() {
      override fun takeLeadership(selector: LeaderSelector) {
        release.await()
      }
    }

  init {
    "waitOnLeadershipComplete(timeout) returns false at its timeout for a standby" {
      connectToEtcd(urls) { client ->
        val path = "$base/timed-wait"
        client.deleteChildren(path)
        val release = CountDownLatch(1)
        val leader = LeaderSelector(client, path, blockingLeader(release)).start()
        val standby = LeaderSelector(client, path, LeaderSelectorListenerAdapter())
        try {
          pollUntil(10.seconds) { leader.isLeader } shouldBe true
          standby.start()
          val done = CountDownLatch(1)
          var result: Boolean? = null
          thread(isDaemon = true) {
            result = standby.waitOnLeadershipComplete(1.seconds)
            done.countDown()
          }
          withClue("the timed wait ignored its timeout") { done.await(10, TimeUnit.SECONDS) shouldBe true }
          result shouldBe false
        } finally {
          release.countDown()
          standby.close()
          leader.close()
        }
        client.deleteChildren(path)
      }
    }

    "close() called from inside takeLeadership returns" {
      connectToEtcd(urls) { client ->
        val path = "$base/close-inside"
        client.deleteChildren(path)
        val closedInside = CountDownLatch(1)
        val selector =
          LeaderSelector(
            client,
            path,
            object : LeaderSelectorListenerAdapter() {
              override fun takeLeadership(selector: LeaderSelector) {
                selector.close()
                closedInside.countDown()
              }
            },
          )
        selector.start()
        withClue("close() inside takeLeadership never returned") {
          closedInside.await(10, TimeUnit.SECONDS) shouldBe true
        }
        client.deleteChildren(path)
      }
    }

    "a candidate closed without ever winning can be started again" {
      connectToEtcd(urls) { client ->
        val path = "$base/restart-standby"
        client.deleteChildren(path)
        val release = CountDownLatch(1)
        val leader = LeaderSelector(client, path, blockingLeader(release)).start()
        val standby = LeaderSelector(client, path, LeaderSelectorListenerAdapter())
        try {
          pollUntil(10.seconds) { leader.isLeader } shouldBe true
          standby.start()
          standby.close()
          shouldNotThrowAny { standby.start() }
        } finally {
          release.countDown()
          standby.close()
          leader.close()
        }
        client.deleteChildren(path)
      }
    }

    "a restarted selector does not report the previous candidacy's LOST connection state" {
      connectToEtcd(urls) { client ->
        val path = "$base/restart-connection-state"
        client.deleteChildren(path)
        val listener =
          object : LeaderSelectorListenerAdapter() {
            override fun takeLeadership(selector: LeaderSelector) {
              selector.waitUntilFinished()
            }
          }
        LeaderSelector(client, path, listener).use { selector ->
          selector.start()
          pollUntil(10.seconds) { selector.isLeader } shouldBe true
          selector.stepDownFromLeadership(null) // lease lost: connectionState goes LOST
          selector.connectionState shouldBe ConnectionState.LOST
          selector.waitOnLeadershipComplete(10.seconds) shouldBe true
          selector.start()
          selector.connectionState shouldBe ConnectionState.CONNECTED
        }
        client.deleteChildren(path)
      }
    }

    "close() on a selector that was never started does not throw" {
      connectToEtcd(urls) { client ->
        shouldNotThrowAny { LeaderSelector(client, "$base/never-started", LeaderSelectorListenerAdapter()).close() }
      }
    }

    "LeaderObserver replays the leader after a recovery only when events could have been missed" {
      connectToEtcd(urls) { client ->
        val path = "$base/observer-replay"
        client.deleteChildren(path)
        client.putValue(ElectionPaths.leaderKey(path), "A:" + "x".repeat(EtcdConnector.TOKEN_LENGTH))
        LeaderObserver(client, path).use { observer ->
          val recorded = RecordingListener().also { observer.addListener(it) }
          val key = ElectionPaths.leaderKey(path)
          observer.onRecovery(WatchRecoveryEvent.Resubscribed(key, resumeRevision = 100L)) // lossless
          withClue("a lossless resubscribe replayed the leader") { recorded.takes.size shouldBe 0 }
          observer.onRecovery(WatchRecoveryEvent.Resubscribed(key, resumeRevision = 0L)) // gap possible
          recorded.takes shouldBe listOf("A")
        }
        client.deleteChildren(path)
      }
    }

    "LeaderObserver reports a failed re-read after recovery to its listeners" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/observer-reread-failure"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        LeaderObserver(client, path).use { observer ->
          val recorded = RecordingListener().also { observer.addListener(it) }
          client.beforeGet.store { throw StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("denied")) }
          shouldNotThrowAny { observer.onRecovery(WatchRecoveryEvent.Resubscribed(ElectionPaths.leaderKey(path), 0L)) }
          recorded.errors.size shouldBe 1
          withClue("an unknown leader was reported as a hand-off") {
            recorded.takes.size shouldBe 0
            recorded.relinquishes.load() shouldBe 0
          }
          observer.exceptions.shouldNotBeEmpty()
        }
      }
    }
  }
}
