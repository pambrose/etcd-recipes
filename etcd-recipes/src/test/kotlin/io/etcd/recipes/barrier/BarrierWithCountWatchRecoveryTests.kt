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

package io.etcd.recipes.barrier

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Txn
import io.etcd.jetcd.Watch
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.support.CloseableClient
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.pollUntil
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a parked [DistributedBarrierWithCount.waitOnBarrier] through a fatal watch
 * death with a mocked jetcd [Client]. A ready-key DELETE that happened while the
 * stream was dead must release the waiter after recovery (via the checkWaiterCount
 * re-probe); an abandoned recovery must fail the wait instead of parking it.
 */
class BarrierWithCountWatchRecoveryTests : StringSpec() {
  /**
   * Transactions arrive in order: #1 ready-CAS (result unused), #2 waiting-path CAS
   * (must win), then presence probes for the ready key. Probes up to
   * [readyPresentProbes] report the ready key present; later ones report it absent.
   */
  private class CountBarrierMocks(
    private val readyPresentProbes: Int,
  ) {
    val listeners = CopyOnWriteArrayList<Watch.Listener>()
    val options = CopyOnWriteArrayList<WatchOption>()
    private val txnCount = AtomicInt(0)

    val client: Client =
      mockk {
        every { kvClient } returns
          mockk<KV> {
            every { txn() } answers {
              val n = txnCount.incrementAndFetch()
              val succeeded = if (n <= 2) true else (n - 2) <= readyPresentProbes
              mockk<Txn> {
                every { If(*anyVararg()) } returns this
                every { Then(*anyVararg()) } returns this
                every { commit() } returns
                  CompletableFuture.completedFuture(mockk<TxnResponse> { every { isSucceeded } returns succeeded })
              }
            }
            every { get(any<ByteSequence>(), any()) } returns
              CompletableFuture.completedFuture(
                mockk<GetResponse> {
                  every { count } returns 1L // waiter count stays below memberCount
                  every { kvs } returns emptyList()
                  every { isMore } returns false
                  every { header } returns mockk { every { revision } returns OBSERVED_REV }
                },
              )
            every { delete(any<ByteSequence>()) } returns
              CompletableFuture.completedFuture(mockk<DeleteResponse>())
          }
        every { leaseClient } returns
          mockk<Lease> {
            every { grant(any()) } returns
              CompletableFuture.completedFuture(mockk<LeaseGrantResponse> { every { id } returns 7L })
            every { keepAlive(7L, any()) } returns mockk<CloseableClient>(relaxed = true)
            every { revoke(any()) } returns CompletableFuture.completedFuture(mockk())
          }
        every { watchClient } returns
          mockk<Watch> {
            every { watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) } answers {
              options += secondArg<WatchOption>()
              listeners += thirdArg<Watch.Listener>()
              mockk<Watch.Watcher>(relaxed = true)
            }
          }
      }

    companion object {
      /** Revision the pre-subscribe read observes; the prefix watch must anchor at +1. */
      const val OBSERVED_REV = 100L
    }
  }

  private fun Watch.Listener.die() {
    onError(RuntimeException("fatal watch error"))
    onCompleted()
  }

  init {
    "waitOnBarrier anchors its prefix watch at the observed revision" {
      // The pre-subscribe read saw the barrier state at OBSERVED_REV; a /ready DELETE or
      // waiter PUT landing before the watch goes live would be lost by an un-anchored
      // watch. The prefix watch must start at OBSERVED_REV + 1.
      val mocks = CountBarrierMocks(readyPresentProbes = Int.MAX_VALUE) // ready stays set → parks
      DistributedBarrierWithCount(mocks.client, "/barrier/count", memberCount = 2).use { barrier ->
        barrier.waitOnBarrier(500.milliseconds)
        mocks.options.size shouldBe 1
        mocks.options.first().revision shouldBe CountBarrierMocks.OBSERVED_REV + 1
        mocks.options.first().isPrefix shouldBe true
      }
    }

    "counted waiter releases after recovery when the ready DELETE happened during the outage" {
      // Ready-key probes: 2 present (initial checkWaiterCount + pre-park recheck),
      // then absent (deleted during the dead-stream window).
      val mocks = CountBarrierMocks(readyPresentProbes = 2)
      val released = AtomicReference<Boolean?>(null)
      val error = AtomicReference<Throwable?>(null)

      DistributedBarrierWithCount(mocks.client, "/barrier/count", memberCount = 2).use { barrier ->
        val waiter =
          thread(name = "count-barrier-waiter") {
            try {
              released.store(barrier.waitOnBarrier(1.minutes))
            } catch (e: Throwable) {
              error.store(e)
            }
          }

        pollUntil(5.seconds) { mocks.listeners.size == 1 } shouldBe true
        mocks.listeners.first().die()

        pollUntil(10.seconds) { released.load() != null || error.load() != null } shouldBe true
        error.load() shouldBe null
        released.load() shouldBe true
        waiter.join(5_000)
      }
    }

    "counted waiter fails fast when watch recovery is abandoned" {
      // Ready key stays present; recovery disabled: the wait must error, not park.
      val mocks = CountBarrierMocks(readyPresentProbes = Int.MAX_VALUE)
      val released = AtomicReference<Boolean?>(null)
      val error = AtomicReference<Throwable?>(null)

      DistributedBarrierWithCount(
        mocks.client,
        "/barrier/count",
        memberCount = 2,
        resilience = ResilienceConfig.DISABLED,
      ).use { barrier ->
        val waiter =
          thread(name = "count-barrier-waiter") {
            try {
              released.store(barrier.waitOnBarrier(1.minutes))
            } catch (e: Throwable) {
              error.store(e)
            }
          }

        pollUntil(5.seconds) { mocks.listeners.size == 1 } shouldBe true
        mocks.listeners.first().die()

        pollUntil(10.seconds) { error.load() != null } shouldBe true
        error.load().shouldBeInstanceOf<EtcdRecipeRuntimeException>()
        released.load() shouldBe null
        waiter.join(5_000)
      }
    }
  }
}
