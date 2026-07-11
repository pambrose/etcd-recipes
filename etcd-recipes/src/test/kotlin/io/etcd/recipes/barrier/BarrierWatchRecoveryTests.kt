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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
    private val probeCount = AtomicInteger(0)

    val client: Client =
      mockk {
        every { kvClient } returns
          mockk<KV> {
            every { txn() } answers {
              val present = probeCount.incrementAndGet() <= presentProbes
              mockk<Txn> {
                every { If(*anyVararg()) } returns this
                every { Then(*anyVararg()) } returns this
                every { commit() } returns
                  CompletableFuture.completedFuture(mockk<TxnResponse> { every { isSucceeded } returns present })
              }
            }
          }
        every { watchClient } returns
          mockk<Watch> {
            every { watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) } answers {
              listeners += thirdArg<Watch.Listener>()
              mockk<Watch.Watcher>(relaxed = true)
            }
          }
      }
  }

  private fun Watch.Listener.die() {
    onError(RuntimeException("fatal watch error"))
    onCompleted()
  }

  init {
    "waiter releases after recovery when the barrier DELETE happened during the outage" {
      // Probe #1 (presence at wait start): present. Later probes (recovery recheck): absent.
      val mocks = BarrierMocks(presentProbes = 1)
      val released = AtomicReference<Boolean?>()
      val error = AtomicReference<Throwable?>()

      DistributedBarrier(mocks.client, "/barrier/recovery").use { barrier ->
        val waiter =
          thread(name = "barrier-waiter") {
            try {
              released.set(barrier.waitOnBarrier(1.minutes))
            } catch (e: Throwable) {
              error.set(e)
            }
          }

        pollUntil(5.seconds) { mocks.listeners.size == 1 } shouldBe true
        mocks.listeners.first().die()

        pollUntil(10.seconds) { released.get() != null || error.get() != null } shouldBe true
        error.get() shouldBe null
        released.get() shouldBe true
        waiter.join(5_000)
      }
    }

    "waiter fails fast when watch recovery is abandoned" {
      // Barrier stays present; recovery disabled: the wait must error, not park.
      val mocks = BarrierMocks(presentProbes = Int.MAX_VALUE)
      val released = AtomicReference<Boolean?>()
      val error = AtomicReference<Throwable?>()

      DistributedBarrier(
        mocks.client,
        "/barrier/recovery",
        resilience = ResilienceConfig.DISABLED,
      ).use { barrier ->
        val waiter =
          thread(name = "barrier-waiter") {
            try {
              released.set(barrier.waitOnBarrier(1.minutes))
            } catch (e: Throwable) {
              error.set(e)
            }
          }

        pollUntil(5.seconds) { mocks.listeners.size == 1 } shouldBe true
        mocks.listeners.first().die()

        pollUntil(10.seconds) { error.get() != null } shouldBe true
        error.get().shouldBeInstanceOf<EtcdRecipeRuntimeException>()
        released.get() shouldBe null
        waiter.join(5_000)
      }
    }
  }
}
