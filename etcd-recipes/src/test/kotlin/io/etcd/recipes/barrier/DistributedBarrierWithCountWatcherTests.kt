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

import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

// Regression test for: DistributedBarrierWithCount.waitOnBarrier shadowed the
// instance `waitingPath` directory with a per-client unique path, so the
// watcher's startsWith() check could only match this client's own waiter key.
// Peer PUTs under /barrier/waiting/* never triggered checkWaiterCount(), so a
// barrier could hang when peer joins were the event that brought waiter count
// up to memberCount.
class DistributedBarrierWithCountWatcherTests : StringSpec() {
  init {
    "watcherDetectsPeerJoinsUnderWaitingPrefix" {
      val path = "/barriers/DistributedBarrierWithCountWatcherTests"

      connectToEtcd(urls) { client ->
        client.deleteChildren(path)

        val released = AtomicBoolean(false)
        val finished = CountDownLatch(1)

        // memberCount = 2: the lone client below + a manually-injected peer.
        val barrier = DistributedBarrierWithCount(client, path, memberCount = 2)

        // Use a daemon thread so a hung waitOnBarrier (bug present) does not
        // keep the test JVM alive. The inner timeout (8s) is shorter than the
        // outer await (12s) so we can distinguish a real release from a
        // timeout: only a real release sets `released = true`.
        thread(isDaemon = true, name = "barrier-waiter") {
          try {
            if (barrier.waitOnBarrier(8.seconds)) released.store(true)
          } finally {
            finished.countDown()
          }
        }

        // Let the barrier client set up: create /ready, its own waiter,
        // observe waiterCount=1, and start the watcher.
        Thread.sleep(2_000)

        // Manually inject a peer waiter directly via etcd, bypassing the
        // recipe. This makes total waiterCount = 2 == memberCount. Only the
        // watcher's PUT-on-waiting-prefix branch can react to this event;
        // with the bug, that branch never fires for peer keys, so the
        // barrier's own client never re-runs checkWaiterCount() and /ready
        // is never deleted -> the lone waiter hangs until its inner timeout.
        val peerKey = path.appendToPath("waiting").appendToPath("manualPeer:abc")
        val lease = client.leaseGrant(30.seconds)
        client.transaction {
          Then(peerKey.setTo("manualPeer", putOption { withLeaseId(lease.id) }))
        }

        finished.await(12, TimeUnit.SECONDS) shouldBe true
        released.load() shouldBe true

        client.deleteChildren(path)
      }
    }

    // Companion to the test above: the watcher's waiting-prefix branch only
    // fires on PUT, so a peer leaving (DELETE under waiting/*) must NOT cause
    // the barrier to release when waiterCount has not reached memberCount.
    // memberCount = 3 with the lone client + one injected peer → waiterCount
    // tops out at 2; deleting the peer leaves a single waiter and the
    // barrier must continue waiting until its inner timeout.
    "watcherIgnoresPeerLeavesUnderWaitingPrefix" {
      val path = "/barriers/DistributedBarrierWithCountWatcherTests-peerLeave"

      connectToEtcd(urls) { client ->
        client.deleteChildren(path)

        val released = AtomicBoolean(false)
        val finished = CountDownLatch(1)

        val barrier = DistributedBarrierWithCount(client, path, memberCount = 3)

        thread(isDaemon = true, name = "barrier-waiter-peerLeave") {
          try {
            if (barrier.waitOnBarrier(6.seconds)) released.store(true)
          } finally {
            finished.countDown()
          }
        }

        // Let the lone client register its waiter and start the watcher.
        Thread.sleep(2_000)

        val peerKey = path.appendToPath("waiting").appendToPath("manualPeer:xyz")
        val lease = client.leaseGrant(30.seconds)
        client.transaction {
          Then(peerKey.setTo("manualPeer", putOption { withLeaseId(lease.id) }))
        }

        // Give the watcher time to observe the peer PUT (and run
        // checkWaiterCount, which sees 2 < 3 and does nothing), then remove
        // the peer. The watcher will see a DELETE under waiting/*; the fixed
        // branch logic must ignore it.
        Thread.sleep(1_000)
        client.deleteKey(peerKey)

        // Inner timeout is 6s; outer await is 10s. A real release would set
        // released=true before the outer await wakes; a timeout-driven
        // finish leaves released=false.
        finished.await(10, TimeUnit.SECONDS) shouldBe true
        released.load() shouldBe false

        client.deleteChildren(path)
      }
    }
  }
}
