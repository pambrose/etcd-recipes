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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class LeaderSelectorCloseDeadlockTests : StringSpec() {
    private val path = "/election/${javaClass.simpleName}"

    private fun blockingLeaderSelector(
      client: io.etcd.jetcd.Client,
      latchRef: AtomicReference<CountDownLatch>,
    ) = LeaderSelector(
        client,
        path,
        takeLeadershipBlock = { selector ->
            latchRef.get().countDown()
            // Block until close() signals completion. Pre-fix attemptToBecomeLeader
            // held the instance monitor across this call, so close() (also
            // @Synchronized on the instance) could never run doClose() to flip the
            // finished signal — a permanent deadlock.
            selector.waitUntilFinished()
        },
        leaseTtlSecs = 2L,
    )

    init {
        // Boot the (possibly Testcontainers) etcd fixture outside the tight per-test
        // invocation timeouts below: the first `urls` access lazily starts the etcd +
        // Ryuk containers, which can take over a minute on a busy Docker daemon.
        beforeSpec { urls }

        // The bounded join + isAlive assertion turn a regression into a clean FAIL
        // (closer thread still alive after the timeout) rather than a hung suite.
        "close() releases a takeLeadership blocked on waitUntilFinished()".config(timeout = 60.seconds) {
            connectToEtcd(urls) { client ->
                val latchRef = AtomicReference(CountDownLatch(1))
                val selector = blockingLeaderSelector(client, latchRef)

                selector.start()
                latchRef.get().await(20, TimeUnit.SECONDS) shouldBe true

                val closer = thread { selector.close() }
                closer.join(20_000)

                closer.isAlive shouldBe false
                selector.isFinished shouldBe true
                selector.hasExceptions shouldBe false
            }
        }

        // Exercises the deadlock fix across start()/close() reuse, which also drives
        // the electionLock-guarded reset path on every cycle.
        "close() releases a blocked leader across start()/close() reuse".config(timeout = 90.seconds) {
            connectToEtcd(urls) { client ->
                val latchRef = AtomicReference(CountDownLatch(1))
                val selector = blockingLeaderSelector(client, latchRef)

                repeat(3) {
                    latchRef.set(CountDownLatch(1))
                    selector.start()
                    latchRef.get().await(20, TimeUnit.SECONDS) shouldBe true

                    val closer = thread { selector.close() }
                    closer.join(20_000)

                    closer.isAlive shouldBe false
                    selector.isFinished shouldBe true
                }

                selector.hasExceptions shouldBe false
            }
        }
    }
}
