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

import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.etcd.recipes.coroutines.leadershipAsFlow
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * A `LeaderSelector` candidacy runs every election attempt and its term on one thread,
 * which the leader-key watch only signals:
 *
 * - `close()` waits for a term won through the watch, as it does for one won at `start()`;
 * - a step-down can't let a second term start while the first is still unwinding;
 * - an attempt that fails (not a lost CAS) is retried rather than leaving the election
 *   leaderless;
 * - `start()` can't hang: not on a single-thread executor, not on a closed client, and a
 *   rejected start leaves the selector closable;
 * - the leader-key watches (selector, observer, and flow) are anchored at the revision
 *   they read, so a hand-off during setup isn't missed.
 */
class LeaderSelectorCandidacyTests : StringSpec() {
  private val base = "/election/${javaClass.simpleName}"

  private fun blockingLeader(release: CountDownLatch) =
    object : LeaderSelectorListenerAdapter() {
      override fun takeLeadership(selector: LeaderSelector) {
        release.await()
      }
    }

  // Runs [block] on a daemon thread; true if it finished within [seconds].
  private fun finishesWithin(
    seconds: Long,
    block: () -> Unit,
  ): Boolean {
    val done = CountDownLatch(1)
    thread(isDaemon = true) {
      runCatching(block)
      done.countDown()
    }
    return done.await(seconds, TimeUnit.SECONDS)
  }

  init {
    "close() waits for a term won through the leader-key watch" {
      connectToEtcd(urls) { client ->
        val path = "$base/close-waits"
        client.deleteChildren(path)
        val releaseA = CountDownLatch(1)
        val a = LeaderSelector(client, path, blockingLeader(releaseA)).start()
        pollUntil(10.seconds) { a.isLeader } shouldBe true
        val termStarted = CountDownLatch(1)
        val termEnded = AtomicBoolean(false)
        val b =
          LeaderSelector(
            client,
            path,
            object : LeaderSelectorListenerAdapter() {
              override fun takeLeadership(selector: LeaderSelector) {
                termStarted.countDown()
                // 8 s of work that doesn't watch for the end of its term
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (System.nanoTime() < until) runCatching { Thread.sleep(100) }
                termEnded.store(true)
              }
            },
          ).start()
        releaseA.countDown()
        a.close() // B wins through the DELETE
        withClue("B never won") { termStarted.await(20, TimeUnit.SECONDS) shouldBe true }
        b.close()
        withClue("close() returned while the term was still running") { termEnded.load() shouldBe true }
        withClue("close() returned with the leader key still held") {
          client.getValue(ElectionPaths.leaderKey(path)).shouldBeNull()
        }
        client.deleteChildren(path)
      }
    }

    "a step-down can't start a second term while the first is unwinding" {
      connectToEtcd(urls) { client ->
        val path = "$base/no-reentry"
        client.deleteChildren(path)
        val entered = AtomicInt(0)
        val release = CountDownLatch(1)
        val selector =
          LeaderSelector(
            client,
            path,
            object : LeaderSelectorListenerAdapter() {
              override fun takeLeadership(selector: LeaderSelector) {
                entered.incrementAndFetch()
                while (release.count > 0) runCatching { release.await() } // slow to unwind
              }
            },
          ).start()
        pollUntil(10.seconds) { selector.isLeader } shouldBe true
        selector.stepDownFromLeadership(null) // the lease is lost...
        client.deleteKey(ElectionPaths.leaderKey(path)) // ...and etcd deleted the leader key
        Thread.sleep(3_000)
        withClue("a second term started on the same selector") { entered.load() shouldBe 1 }
        release.countDown()
        selector.close()
        client.deleteChildren(path)
      }
    }

    "an election attempt that fails is retried, not dropped" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/retry"
        etcd.deleteChildren(path)
        val releaseA = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val a = LeaderSelector(etcd, path, blockingLeader(releaseA)).start()
        pollUntil(10.seconds) { a.isLeader } shouldBe true
        val client = HookedClient(etcd)
        val b = LeaderSelector(client, path, blockingLeader(releaseB), clientId = "retrying").start()
        pollUntil(10.seconds) { LeaderSelector.getParticipants(etcd, path).any { it.clientId == "retrying" } } shouldBe
          true
        // B's claim when A's key goes fails once, as during an etcd blip
        client.beforeTxn.store { throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("leader changed")) }
        releaseA.countDown()
        a.close()
        withClue("the failed attempt was never retried; the election stayed leaderless") {
          pollUntil(15.seconds) { b.isLeader } shouldBe true
        }
        releaseB.countDown()
        b.close()
        etcd.deleteChildren(path)
      }
    }

    "start() returns on a single-thread executor" {
      connectToEtcd(urls) { client ->
        val path = "$base/one-thread"
        client.deleteChildren(path)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val selector = LeaderSelector(client, path, LeaderSelectorListenerAdapter(), userExecutor = pool)
        try {
          withClue("start() hung on a single-thread executor") { finishesWithin(10) { selector.start() } shouldBe true }
        } finally {
          finishesWithin(10) { selector.close() }
          pool.shutdownNow()
        }
        client.deleteChildren(path)
      }
    }

    "start() on a closed client fails instead of hanging" {
      val closed = connectToEtcd(urls)
      closed.close()
      val selector = LeaderSelector(closed, "$base/closed-client", LeaderSelectorListenerAdapter())
      var outcome: Result<LeaderSelector>? = null
      withClue("start() hung on a closed client") {
        finishesWithin(10) { outcome = runCatching { selector.start() } } shouldBe true
      }
      (outcome!!.isFailure) shouldBe true
    }

    "a start() rejected by its executor leaves the selector closable" {
      connectToEtcd(urls) { client ->
        val pool = Executors.newFixedThreadPool(3)
        pool.shutdown()
        val selector = LeaderSelector(client, "$base/rejected", LeaderSelectorListenerAdapter(), userExecutor = pool)
        (runCatching { selector.start() }.isFailure) shouldBe true
        withClue("close() hung after a rejected start()") { finishesWithin(5) { selector.close() } shouldBe true }
      }
    }

    "the selector, observer, and flow anchor their leader-key watches" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/anchored"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val leaderKey = ElectionPaths.leaderKey(path)

        fun lastLeaderWatchRevision() = client.watchOptions.last { (key, _) -> key == leaderKey }.second.revision

        val release = CountDownLatch(1)
        LeaderSelector(client, path, blockingLeader(release)).start().use {
          try {
            withClue("the selector's leader watch isn't anchored") { lastLeaderWatchRevision() shouldBeGreaterThan 0L }
            LeaderObserver(client, path).start().use {
              withClue("the observer's leader watch isn't anchored") {
                lastLeaderWatchRevision() shouldBeGreaterThan 0L
              }
            }
            runBlocking { client.leadershipAsFlow(path).first() }
            withClue("leadershipAsFlow's leader watch isn't anchored") {
              lastLeaderWatchRevision() shouldBeGreaterThan 0L
            }
          } finally {
            release.countDown() // let the term end, or close() waits for it
          }
        }
        etcd.deleteChildren(path)
      }
    }
  }
}
