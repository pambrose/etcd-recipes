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
import io.etcd.jetcd.Txn
import io.etcd.jetcd.Watch
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.options.WatchOption
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
 * Drives a parked [DistributedBarrier.waitOnBarrier] through a fatal watch death with
 * a mocked jetcd [Client]. A barrier DELETE that happened while the watch stream was
 * dead must release the waiter after recovery; an abandoned recovery must fail the
 * wait instead of parking it until timeout.
 */
class BarrierWatchRecoveryTests : StringSpec() {
  /**
   * The barrier's presence is probed via CAS transactions (isKeyPresent). The first
   * [presentProbes] probes report the barrier present; later ones report it absent.
   */
  private class BarrierMocks(
    private val presentProbes: Int,
  ) {
    val listeners = CopyOnWriteArrayList<Watch.Listener>()
    val options = CopyOnWriteArrayList<WatchOption>()
    private val probeCount = AtomicInt(0)

    val client: Client =
      mockk {
        every { kvClient } returns
          mockk<KV> {
            every { txn() } answers {
              val present = probeCount.incrementAndFetch() <= presentProbes
              mockk<Txn> {
                every { If(*anyVararg()) } returns this
                every { Then(*anyVararg()) } returns this
                every { commit() } returns
                  CompletableFuture.completedFuture(
                    mockk<TxnResponse> {
                      every { isSucceeded } returns present
                      every { header } returns mockk { every { revision } returns OBSERVED_REV }
                    },
                  )
              }
            }
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
      /** Revision the presence probe observes the barrier at; the watch must anchor at +1. */
      const val OBSERVED_REV = 100L
    }
  }

  private fun Watch.Listener.die() {
    onError(RuntimeException("fatal watch error"))
    onCompleted()
  }

  init {
    "waitOnBarrier anchors its DELETE-watch at the observed-present revision" {
      // The presence probe saw the barrier set at OBSERVED_REV; a DELETE landing before
      // the watch goes live would be lost by an un-anchored watch. The watch must start
      // at OBSERVED_REV + 1 so the release is (re)delivered.
      val mocks = BarrierMocks(presentProbes = Int.MAX_VALUE) // stays present → parks until timeout
      DistributedBarrier(mocks.client, "/barrier/recovery").use { barrier ->
        barrier.waitOnBarrier(500.milliseconds)
        mocks.options.size shouldBe 1
        mocks.options.first().revision shouldBe BarrierMocks.OBSERVED_REV + 1
        mocks.options.first().isNoPut shouldBe true
      }
    }

    "waiter releases after recovery when the barrier DELETE happened during the outage" {
      // Probe #1 (presence at wait start): present. Later probes (recovery recheck): absent.
      val mocks = BarrierMocks(presentProbes = 1)
      val released = AtomicReference<Boolean?>(null)
      val error = AtomicReference<Throwable?>(null)

      DistributedBarrier(mocks.client, "/barrier/recovery").use { barrier ->
        val waiter =
          thread(name = "barrier-waiter") {
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

    "waiter fails fast when watch recovery is abandoned" {
      // Barrier stays present; recovery disabled: the wait must error, not park.
      val mocks = BarrierMocks(presentProbes = Int.MAX_VALUE)
      val released = AtomicReference<Boolean?>(null)
      val error = AtomicReference<Throwable?>(null)

      DistributedBarrier(
        mocks.client,
        "/barrier/recovery",
        resilience = ResilienceConfig.DISABLED,
      ).use { barrier ->
        val waiter =
          thread(name = "barrier-waiter") {
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
