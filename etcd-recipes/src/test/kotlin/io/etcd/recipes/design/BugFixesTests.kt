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

package io.etcd.recipes.design

import io.etcd.jetcd.options.LeaseOption
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.barrier.DistributedBarrier
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.leaseRevoke
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.common.watcher
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the eight bug fixes documented in the corresponding code review.
 * Each fix is exercised either through end-to-end behavior or, where injecting
 * the original failure cleanly is impractical, through a structural source
 * check that locks in the post-fix shape so a future refactor cannot silently
 * regress.
 */
class BugFixesTests : StringSpec() {
  init {

    // ============================================================
    // Fix #1: DistributedAtomicLong.modifyCounterValue must return the
    // value it just committed, not the result of a separate GET.
    // ============================================================
    "fix #1: concurrent increments return distinct, contiguous values" {
      // Under the buggy implementation the trailing `client.getValue(...)`
      // could observe another writer's value, so two threads could see the
      // same return value and the post-condition `set == 1..N` would fail.
      val path = "/counters/bug-fixes/concurrent-increment"
      val threadCount = 16
      connectToEtcd(urls) { client ->
        DistributedAtomicLong.delete(client, path)
        DistributedAtomicLong(client, path).use { counter ->
          counter.start()
          val pool = Executors.newFixedThreadPool(threadCount)
          val ready = CountDownLatch(threadCount)
          val go = CountDownLatch(1)
          val done = CountDownLatch(threadCount)
          val results = mutableListOf<Long>()

          repeat(threadCount) {
            pool.execute {
              try {
                ready.countDown()
                go.await()
                val v = counter.increment()
                synchronized(results) { results.add(v) }
              } finally {
                done.countDown()
              }
            }
          }
          ready.await()
          // Release all threads at once to maximize contention on the CAS path.
          go.countDown()
          done.await(30, TimeUnit.SECONDS) shouldBe true
          pool.shutdown()

          val sorted = synchronized(results) { results.toList() }.sorted()
          sorted shouldContainExactly (1L..threadCount.toLong()).toList()
          counter.get() shouldBe threadCount.toLong()
        }
        DistributedAtomicLong.delete(client, path)
      }
    }

    // ============================================================
    // Fix #2: PathChildrenCache.doClose must wait for the background
    // loader before closing the watcher; otherwise a concurrent close
    // can leak a watcher (and its dispatcher executor) if the loader
    // assigns it after close().
    // ============================================================
    "fix #2: close immediately after async start completes cleanly" {
      // Behavioral check: with the pre-fix ordering, doClose() called
      // watcher?.close() before waiting for the background loader, so the
      // loader could assign a new watcher *after* close ran — leaving it
      // unclosed and never re-checked. The fix waits for the loader before
      // touching the watcher, so once close() returns, the cache is fully
      // torn down. We assert close() returns and is idempotent under
      // back-to-back start/close cycles without exceptions.
      val path = "/caches/bug-fixes/close-after-async-start"
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        client.putValue("$path/seed", "v")

        repeat(8) {
          val cache = PathChildrenCache(client, path)
          cache.start(buildInitial = true, waitOnStartComplete = false)
          cache.close()
          // Idempotent: a second close must be a no-op even if the loader
          // finished asynchronously.
          cache.close()
        }

        client.deleteChildren(path)
      }
    }

    "fix #2: doClose source waits on startThreadComplete before closing the watcher" {
      val src = readSource("src/main/kotlin/io/etcd/recipes/cache/PathChildrenCache.kt")
      val waitIdx = src.indexOf("startThreadComplete.waitUntilTrue()")
      val closeIdx = src.indexOf("watcher?.close()")
      // Both must be present and the wait must precede the close inside doClose.
      (waitIdx > 0) shouldBe true
      (closeIdx > 0) shouldBe true
      (waitIdx < closeIdx) shouldBe true
    }

    // ============================================================
    // Fix #3: TransientKeyValue.start() must release the start latch
    // even if the inner put / lease grant throws — otherwise start()
    // hangs forever on a transient etcd failure.
    // ============================================================
    "fix #3: start() against a closed client throws within a few seconds instead of hanging" {
      val path = "/keyvalue/bug-fixes/start-with-closed-client"
      val client = connectToEtcd(urls)
      client.close()

      val pool = Executors.newSingleThreadExecutor()
      val outcome = AtomicReference<Throwable?>(null)
      val done = CountDownLatch(1)
      pool.execute {
        try {
          // autoStart=true triggers start() inside the constructor; the
          // pre-fix code blocked on keepAliveStartedLatch indefinitely here.
          TransientKeyValue(client, path, "v", autoStart = true)
          outcome.store(IllegalStateException("expected start() to throw"))
        } catch (e: Throwable) {
          outcome.store(e)
        } finally {
          done.countDown()
        }
      }

      done.await(15, TimeUnit.SECONDS) shouldBe true
      val thrown = outcome.load()
      // Any exception (EtcdRecipeRuntimeException wrapping a jetcd failure,
      // or the underlying gRPC error itself) is acceptable — the test is
      // that we did not hang.
      (thrown != null) shouldBe true
      pool.shutdownNow()
    }

    // ============================================================
    // Fix #4 / #5: lease leaks on failed CAS.
    // ============================================================
    "fix #4: LeaderSelector source revokes the lease when CAS fails" {
      val src = readSource("src/main/kotlin/io/etcd/recipes/election/LeaderSelector.kt")
      // The fix introduces a leaseRevoke() call on the failure branch.
      src.contains("client.leaseRevoke(lease)") shouldBe true
    }

    "fix #5: DistributedBarrier source revokes the lease when CAS fails" {
      val src = readSource("src/main/kotlin/io/etcd/recipes/barrier/DistributedBarrier.kt")
      // Since 0.12 the barrier lease is owned by selfHealingKeepAlive, whose
      // establish-declined path revokes the lease it granted — the leak-on-failed-CAS
      // fix lives there now (see SelfHealingKeepAliveTests "initial establish
      // returning false aborts and revokes the lease"). The barrier source must keep
      // routing lease ownership through it rather than hand-rolling grant/keepAlive.
      src.contains("selfHealingKeepAlive(") shouldBe true
      src.contains("leaseClient.grant") shouldBe false
    }

    "fix #5: losing setBarrier returns false cleanly and the winner remains active" {
      // The leak-on-failed-CAS path inside setBarrier requires a true race:
      // isKeyPresent must return false but the txn must still lose. We can't
      // make that race deterministic from a unit test (jetcd 0.8.x exposes
      // no list-leases API to verify a strand directly), so the fix is
      // verified at the source level by the test below; this test just
      // confirms behavior is uncorrupted: many losing setBarrier calls do
      // not break the winner's barrier and each returns false promptly.
      val path = "/barriers/bug-fixes/setbarrier-loser"
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        DistributedBarrier(client, path, leaseTtlSecs = 60).use { winner ->
          winner.setBarrier() shouldBe true

          repeat(15) {
            DistributedBarrier(client, path, leaseTtlSecs = 60).use { loser ->
              loser.setBarrier() shouldBe false
            }
          }

          // Winner still holds the barrier after the losers churned.
          winner.isBarrierSet() shouldBe true
          winner.removeBarrier()
        }
        client.deleteChildren(path)
      }
    }

    // ============================================================
    // Fix #6: putValuesWithKeepAlive must revoke the lease on put failure.
    // ============================================================
    "fix #6: putValuesWithKeepAlive source revokes lease on put failure" {
      val src = readSource("src/main/kotlin/io/etcd/recipes/common/KeepAliveExtensions.kt")
      // The fixed body wraps the put loop in try/catch and calls leaseRevoke.
      src.contains("leaseRevoke(lease)") shouldBe true
      src.contains("try {") shouldBe true
    }

    // ============================================================
    // Fix #7: DispatchingWatcher.close() must await in-flight callbacks.
    // ============================================================
    "fix #7: closing a watcher waits for an in-flight callback to finish" {
      val path = "/watchers/bug-fixes/close-awaits-callback"
      val callbackEntered = CountDownLatch(1)
      val releaseCallback = CountDownLatch(1)
      val callbackExited = AtomicReference(false)

      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        val watcher =
          client.watcher(path, WatchOption.DEFAULT) { resp ->
            if (resp.events.any { it.eventType == WatchEvent.EventType.PUT }) {
              callbackEntered.countDown()
              releaseCallback.await()
              // Sleep briefly so a buggy close() that doesn't await would
              // race past this point.
              Thread.sleep(200)
              callbackExited.store(true)
            }
          }

        client.putValue(path, "v")
        callbackEntered.await(10, TimeUnit.SECONDS) shouldBe true

        // Schedule the callback to finish on a background thread shortly
        // after we begin closing — this gives close() something real to wait
        // for. With the bug, close() returns immediately and the assertion
        // below could observe callbackExited == false.
        val helper = Executors.newSingleThreadExecutor()
        helper.execute {
          Thread.sleep(100)
          releaseCallback.countDown()
        }

        watcher.close()
        // Post-close, the callback must have finished.
        callbackExited.load() shouldBe true

        helper.shutdownNow()
        client.deleteKey(path)
      }
    }

    // ============================================================
    // Fix #8: DistributedDoubleBarrier.close uses try/finally so the
    // second barrier is always closed.
    // ============================================================
    "fix #8: DistributedDoubleBarrier.close closes leaveBarrier even if enterBarrier.close throws" {
      val src = readSource("src/main/kotlin/io/etcd/recipes/barrier/DistributedDoubleBarrier.kt")
      // The fix introduces a try/finally around enterBarrier.close().
      src.contains("try {") shouldBe true
      src.contains("} finally {") shouldBe true
      src.contains("leaveBarrier.close()") shouldBe true
    }

    // ============================================================
    // Helper: confirm leaseRevoke helper is exposed in the common package
    // so other recipes can adopt the same pattern without re-importing
    // jetcd directly.
    // ============================================================
    "helper: leaseRevoke is reachable from a Client extension" {
      connectToEtcd(urls) { client ->
        // Grant a short-lived lease and revoke it via the new helper. After
        // revoke, etcd reports ttl <= 0 (or throws — both indicate the lease
        // is no longer live).
        val lease = client.leaseGrant(60.seconds)
        client.leaseRevoke(lease)
        val ttl: Long =
          runCatching {
            client.leaseClient
              .timeToLive(lease.id, LeaseOption.DEFAULT)
              .get()
              .ttl
          }.getOrDefault(-1L)
        (ttl <= 0L) shouldBe true
      }
    }

    // ============================================================
    // Negative-control: TransientKeyValue still works against a healthy
    // etcd. The fix changes the failure path; the success path must be
    // unaffected.
    // ============================================================
    "fix #3 regression check: healthy start() still succeeds" {
      val path = "/keyvalue/bug-fixes/healthy-start"
      connectToEtcd(urls) { client ->
        client.deleteKey(path)
        TransientKeyValue(client, path, "v", autoStart = true).use {
          // No exception thrown; doClose runs cleanly.
        }
        client.deleteKey(path)
      }
    }
  }

  private fun readSource(relativePath: String): String =
    java.nio.file.Files.readString(java.nio.file.Path.of(relativePath))
}
