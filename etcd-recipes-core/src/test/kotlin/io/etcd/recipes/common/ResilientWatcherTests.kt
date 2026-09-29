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

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.Watch
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchResponse
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the resilient watcher recovery machinery. jetcd's [Watch] is mocked
 * (in the style of [FailingLeaseMocks]) so tests can kill the stream deterministically:
 * jetcd reports a fatal watch death by invoking `onError` followed by `onCompleted`
 * on the registered [Watch.Listener], which is exactly what these tests drive.
 * No etcd connection is required.
 */
class ResilientWatcherTests : StringSpec() {
  /** Captures every subscribe call the watcher under test makes against jetcd. */
  private class WatchMocks {
    val listeners = CopyOnWriteArrayList<Watch.Listener>()
    val options = CopyOnWriteArrayList<WatchOption>()
    val watchers = CopyOnWriteArrayList<Watch.Watcher>()
    var failSubscribesAfter = Int.MAX_VALUE
    var subscribeFailure: () -> Throwable = { IllegalStateException("subscribe refused") }
    val subscribeAttempts = AtomicInt(0)

    val watch: Watch =
      mockk {
        every { watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) } answers {
          subscribeAttempts.incrementAndFetch()
          if (listeners.size >= failSubscribesAfter) throw subscribeFailure()
          options += secondArg<WatchOption>()
          listeners += thirdArg<Watch.Listener>()
          mockk<Watch.Watcher>(relaxed = true).also { watchers += it }
        }
      }

    val client: Client = mockk { every { watchClient } returns watch }
  }

  private fun eventResponse(vararg modRevisions: Long): WatchResponse =
    mockk {
      every { events } returns
        modRevisions.map { rev ->
          mockk<WatchEvent> {
            every { keyValue } returns mockk<KeyValue> { every { modRevision } returns rev }
          }
        }
      every { isCreatedNotify } returns false
      every { header } returns mockk { every { revision } returns (modRevisions.maxOrNull() ?: 0L) }
    }

  private fun createdResponse(headerRevision: Long): WatchResponse =
    mockk {
      every { events } returns emptyList()
      every { isCreatedNotify } returns true
      every { header } returns mockk { every { revision } returns headerRevision }
    }

  private fun progressResponse(headerRevision: Long): WatchResponse =
    mockk {
      every { events } returns emptyList()
      every { isCreatedNotify } returns false
      every { header } returns mockk { every { revision } returns headerRevision }
    }

  private fun quickRetries() = WatchResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds))

  /** Drives the fatal-death sequence jetcd produces when it abandons a watch. */
  private fun Watch.Listener.die(cause: Throwable = RuntimeException("fatal watch error")) {
    onError(cause)
    onCompleted()
  }

  init {
    "default watcher resubscribes after a fatal stream death" {
      val mocks = WatchMocks()
      mocks.client.watcher("/rw/default") { }.use {
        mocks.listeners.first().die()
        pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
      }
    }

    "a response after a transient error reports the stream as resubscribed" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      mocks.client.watcher("/rw/transient", WatchOption.DEFAULT, quickRetries(), { events += it }, null) { }.use {
        mocks.listeners.first().onNext(eventResponse(7))
        mocks.listeners.first().onError(RuntimeException("stream reset")) // jetcd retries this one itself
        mocks.listeners.first().onNext(eventResponse(8))
        pollUntil(5.seconds) { events.size == 2 } shouldBe true
        (events[0] is WatchRecoveryEvent.Suspended) shouldBe true
        events[1] shouldBe WatchRecoveryEvent.Resubscribed("/rw/transient", 9L)
        mocks.listeners.size shouldBe 1 // no resubscribe of our own: jetcd recovered the stream
      }
    }

    "recovery transitions increment the watch-recovery metric" {
      val mocks = WatchMocks()
      val kinds = CopyOnWriteArrayList<String>()
      val metrics =
        object : EtcdMetrics {
          override fun incrementWatchRecovery(
            kind: String,
            key: String,
          ) {
            kinds += kind
          }
        }
      mocks.client.watcher("/rw/metrics", WatchOption.DEFAULT, quickRetries().withMetrics(metrics), { }, null) { }
        .use {
          mocks.listeners.first().die()
          pollUntil(5.seconds) { kinds.contains("resubscribed") } shouldBe true
          kinds shouldContain "suspended"
        }
    }

    "resubscribe resumes at last-seen event revision + 1" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      mocks.client.watcher("/rw/resume", WatchOption.DEFAULT, quickRetries(), { events += it }, null) { }
        .use {
          mocks.listeners.first().onNext(eventResponse(41, 42))
          mocks.listeners.first().die()
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          mocks.options[1].revision shouldBe 43
          pollUntil(5.seconds) { events.any { e -> e is WatchRecoveryEvent.Resubscribed } } shouldBe true
          events.filterIsInstance<WatchRecoveryEvent.Resubscribed>().first().resumeRevision shouldBe 43
        }
    }

    "progress-notify responses advance the resume revision" {
      val mocks = WatchMocks()
      mocks.client.watcher("/rw/notify", WatchOption.DEFAULT, quickRetries()) { }
        .use {
          mocks.listeners.first().onNext(progressResponse(500))
          mocks.listeners.first().die()
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          mocks.options[1].revision shouldBe 501
        }
    }

    "un-anchored watcher that saw no events resubscribes from current revision" {
      val mocks = WatchMocks()
      mocks.client.watcher("/rw/current", WatchOption.DEFAULT, quickRetries()) { }
        .use {
          mocks.listeners.first().die()
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          mocks.options[1].revision shouldBe 0
        }
    }

    "caller-anchored watcher that saw no events resubscribes from its anchor" {
      val mocks = WatchMocks()
      val anchored = watchOption { withRevision(50) }
      mocks.client.watcher("/rw/anchored", anchored, quickRetries()) { }
        .use {
          mocks.listeners.first().die()
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          mocks.options[1].revision shouldBe 50
        }
    }

    "compaction death invokes resyncWith and anchors the new watch at its revision" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      val compacted = EtcdExceptionFactory.newCompactedException(100)
      mocks.client.watcher("/rw/resync", WatchOption.DEFAULT, quickRetries(), { events += it }, { 106L }) { }
        .use {
          mocks.listeners.first().die(compacted)
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          mocks.options[1].revision shouldBe 106
          pollUntil(5.seconds) { events.any { e -> e is WatchRecoveryEvent.Resynced } } shouldBe true
          val resync = events.filterIsInstance<WatchRecoveryEvent.Resynced>().first()
          resync.compactRevision shouldBe 100
          resync.anchorRevision shouldBe 106
        }
    }

    "compaction death without resyncWith resumes at the compacted revision" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      val compacted = EtcdExceptionFactory.newCompactedException(200)
      mocks.client.watcher("/rw/skip", WatchOption.DEFAULT, quickRetries(), { events += it }, null) { }
        .use {
          mocks.listeners.first().die(compacted)
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          // etcd's compact_revision is the oldest revision a watcher can still receive
          mocks.options[1].revision shouldBe 200
          pollUntil(5.seconds) { events.any { e -> e is WatchRecoveryEvent.Resynced } } shouldBe true
          events.filterIsInstance<WatchRecoveryEvent.Resynced>().first().anchorRevision shouldBe 200
        }
    }

    "a failed resync is retried rather than skipped" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      val resyncs = AtomicInt(0)
      val resync = {
        if (resyncs.incrementAndFetch() <= 2) throw IllegalStateException("resync GET failed")
        300L
      }
      mocks.client.watcher("/rw/resync-retry", WatchOption.DEFAULT, quickRetries(), { events += it }, resync) { }
        .use {
          mocks.listeners.first().die(EtcdExceptionFactory.newCompactedException(250))
          pollUntil(5.seconds) {
            events.any { it is WatchRecoveryEvent.Resynced || it is WatchRecoveryEvent.Resubscribed }
            } shouldBe true
          withClue("a resubscribe skipped the resync after it failed: $events") {
            events.none { it is WatchRecoveryEvent.Resubscribed } shouldBe true
          }
          resyncs.load() shouldBe 3
          mocks.options.last().revision shouldBe 300
        }
    }

    "a recovery whose new stream dies at once keeps spending the same retry budget" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      val bounded = WatchResilience(RetryPolicy.bounded(maxAttempts = 3, delay = 10.milliseconds))
      mocks.client.watcher("/rw/budget", WatchOption.DEFAULT, bounded, { events += it }, null) { }.use {
        var died = 0
        // Every new stream dies before delivering anything; the recovery never took hold
        while (died < 10 && events.none { it is WatchRecoveryEvent.Failed }) {
          pollUntil(5.seconds) { mocks.listeners.size > died || events.any { it is WatchRecoveryEvent.Failed } }
          if (mocks.listeners.size > died) mocks.listeners[died++].die()
        }
        withClue("each resubscribe reset the retry budget; $died deaths") {
          pollUntil(5.seconds) { events.any { it is WatchRecoveryEvent.Failed } } shouldBe true
        }
      }
    }

    "an anchored watch's created notification doesn't advance the resume revision" {
      val mocks = WatchMocks()
      val option = watchOption {
        withRevision(5L)
        withCreateNotify(true)
      }
      mocks.client.watcher("/rw/created-anchored", option, quickRetries(), null, null) { }.use {
        mocks.listeners.first().onNext(createdResponse(headerRevision = 20L))
        mocks.listeners.first().die()
        pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
        withClue("resumed past events 5..20 that were never replayed") { mocks.options[1].revision shouldBe 5L }
      }
    }

    "an un-anchored watch resumes from its creation revision, and hides the forced created notification" {
      val mocks = WatchMocks()
      val delivered = CopyOnWriteArrayList<WatchResponse>()
      mocks.client.watcher("/rw/created-unanchored", WatchOption.DEFAULT, quickRetries(), null, null) {
        delivered += it
      }
        .use {
          withClue("an un-anchored watch didn't ask for its created notification") {
            mocks.options.first().isCreatedNotify shouldBe true
          }
          mocks.listeners.first().onNext(createdResponse(headerRevision = 20L))
          mocks.listeners.first().die()
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          mocks.options[1].revision shouldBe 21L
          withClue("the caller got a created notification it didn't ask for") { delivered.size shouldBe 0 }
        }
    }

    "a closed client ends recovery instead of retrying forever" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      mocks.failSubscribesAfter = 1
      mocks.subscribeFailure = { EtcdExceptionFactory.newClosedWatchClientException() }
      val patient = WatchResilience(RetryPolicy.bounded(maxAttempts = 1_000, delay = 10.milliseconds))
      mocks.client.watcher("/rw/closed-client", WatchOption.DEFAULT, patient, { events += it }, null) { }.use {
        mocks.listeners.first().die()
        withClue("a closed client was retried: $events") {
          pollUntil(5.seconds) { events.any { it is WatchRecoveryEvent.Failed } } shouldBe true
        }
        mocks.subscribeAttempts.load() shouldBe 2
      }
    }

    "recovery listener sees Suspended then Resubscribed" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      mocks.client.watcher("/rw/order", WatchOption.DEFAULT, quickRetries(), { events += it }, null) { }
        .use {
          mocks.listeners.first().die()
          pollUntil(5.seconds) { events.size >= 2 } shouldBe true
          events[0].shouldBeInstanceOf<WatchRecoveryEvent.Suspended>()
          events[1].shouldBeInstanceOf<WatchRecoveryEvent.Resubscribed>()
        }
    }

    "user close does not trigger resubscribe and closes the inner watcher" {
      val mocks = WatchMocks()
      val received = CopyOnWriteArrayList<WatchResponse>()
      val watcher = mocks.client.watcher("/rw/close", WatchOption.DEFAULT, quickRetries()) { received += it }
      mocks.listeners.first().onNext(eventResponse(7))
      pollUntil(5.seconds) { received.size == 1 } shouldBe true
      watcher.close()
      // jetcd fires onCompleted when the inner watcher is closed; that must not recover
      mocks.listeners.first().onCompleted()
      Thread.sleep(100)
      mocks.listeners.size shouldBe 1
      verify { mocks.watchers.first().close() }
      // events arriving after close are dropped, not dispatched
      mocks.listeners.first().onNext(eventResponse(8))
      Thread.sleep(100)
      received.size shouldBe 1
    }

    "retry-policy exhaustion emits Failed and stops" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      mocks.failSubscribesAfter = 1
      val resilience = WatchResilience(RetryPolicy.bounded(maxAttempts = 1, delay = 10.milliseconds))
      mocks.client.watcher("/rw/exhaust", WatchOption.DEFAULT, resilience, { events += it }, null) { }
        .use {
          mocks.listeners.first().die()
          pollUntil(5.seconds) { events.any { e -> e is WatchRecoveryEvent.Failed } } shouldBe true
          // initial subscribe + exactly one failed retry
          verify(exactly = 2) { mocks.watch.watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) }
        }
    }

    "never policy emits Failed without resubscribing" {
      val mocks = WatchMocks()
      val events = CopyOnWriteArrayList<WatchRecoveryEvent>()
      mocks.client.watcher("/rw/never", WatchOption.DEFAULT, WatchResilience.DISABLED, { events += it }, null) { }
        .use {
          mocks.listeners.first().die()
          pollUntil(5.seconds) { events.any { e -> e is WatchRecoveryEvent.Failed } } shouldBe true
          verify(exactly = 1) { mocks.watch.watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) }
        }
    }

    "watch options are preserved across resubscribe" {
      val mocks = WatchMocks()
      val original = watchOption { isPrefix(true).withNoDelete(true).withPrevKV(true) }
      mocks.client.watcher("/rw/opts", original, quickRetries()) { }
        .use {
          mocks.listeners.first().die()
          pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
          with(mocks.options[1]) {
            isPrefix shouldBe true
            isNoDelete shouldBe true
            isPrevKV shouldBe true
          }
          // resilience default asks etcd for progress notifications on every subscribe
          mocks.options[0].isProgressNotify shouldBe true
          mocks.options[1].isProgressNotify shouldBe true
        }
    }

    "events from the replacement watcher flow to the same block" {
      val mocks = WatchMocks()
      val received = CopyOnWriteArrayList<Long>()
      mocks.client.watcher("/rw/flow", WatchOption.DEFAULT, quickRetries()) { resp ->
        resp.events.forEach { received += it.keyValue.modRevision }
      }.use {
        mocks.listeners.first().onNext(eventResponse(41, 42))
        mocks.listeners.first().die()
        pollUntil(5.seconds) { mocks.listeners.size == 2 } shouldBe true
        mocks.listeners[1].onNext(eventResponse(50))
        pollUntil(5.seconds) { received.size == 3 } shouldBe true
        received shouldBe [41L, 42L, 50L]
        received shouldContain 50L
      }
    }
  }
}
