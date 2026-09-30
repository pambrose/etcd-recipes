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

package io.etcd.recipes.lock

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lock and permit lifecycle and semantics:
 *
 * - `close()` that lands while an acquisition is granting its lease aborts it, rather
 *   than letting it acquire on a closed recipe;
 * - a lock failure that can't be retried (permission denied) is thrown, not retried
 *   forever or reported by `tryLock` as a timeout;
 * - a semaphore release gives up this thread's own permit (or its lost one), never
 *   another thread's live permit;
 * - `lock`/`acquire` declare `InterruptedException` for Java;
 * - a `tryLock`/`tryAcquire` deadline bounds its RPCs too;
 * - a release survives one failed revoke.
 */
class LockLifecycleTests : StringSpec() {
  private val base = "/locks/${javaClass.simpleName}"

  private fun denied() = StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("denied"))

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

  // Closes [recipe] from another thread while the calling acquisition is granting its lease.
  private fun closeDuringLeaseGrant(
    client: HookedClient,
    recipe: AutoCloseable,
  ) {
    client.beforeLeaseGrant.store { thread { recipe.close() }.join() }
  }

  private fun leasedUnder(
    client: Client,
    prefix: String,
  ) = client.getChildrenKeys(prefix)

  init {
    "a mutex acquisition that close() lands on is aborted" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/mutex-close"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val mutex = DistributedMutex(client, path)
        closeDuringLeaseGrant(client, mutex)
        shouldThrow<EtcdRecipeRuntimeException> { mutex.lock() }
        withClue("the closed mutex left a lock entry behind") {
          pollUntil(5.seconds) { leasedUnder(etcd, path).isEmpty() } shouldBe true
        }
      }
    }

    "a semaphore acquisition that close() lands on is aborted" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/semaphore-close"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val semaphore = DistributedSemaphore(client, path, 1)
        closeDuringLeaseGrant(client, semaphore)
        shouldThrow<EtcdRecipeRuntimeException> { semaphore.acquire() }
        withClue("the closed semaphore left a holder entry behind") {
          pollUntil(5.seconds) { leasedUnder(etcd, "$path/holders").isEmpty() } shouldBe true
        }
      }
    }

    "a read-write lock acquisition that close() lands on is aborted" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/rwlock-close"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val rwLock = DistributedReadWriteLock(client, path)
        closeDuringLeaseGrant(client, rwLock)
        shouldThrow<EtcdRecipeRuntimeException> { rwLock.writeLock.lock() }
        withClue("the closed read-write lock left an entry behind") {
          pollUntil(5.seconds) { leasedUnder(etcd, path).isEmpty() } shouldBe true
        }
      }
    }

    "a lock failure that can't be retried is thrown, not retried forever" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/denied"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        client.failLocks.store(denied())
        DistributedMutex(client, path).use { mutex ->
          var lockOutcome: Result<Unit>? = null
          withClue("lock() kept retrying a permission failure") {
            finishesWithin(10) { lockOutcome = runCatching { mutex.lock() } } shouldBe true
          }
          (lockOutcome!!.exceptionOrNull() is EtcdRecipeRuntimeException) shouldBe true
          withClue("tryLock reported a permission failure as a timeout") {
            shouldThrow<EtcdRecipeRuntimeException> { mutex.tryLock(5.seconds) }
          }
        }
      }
    }

    "a semaphore release gives up the caller's own permit, never another thread's" {
      connectToEtcd(urls) { client ->
        val path = "$base/owner-release"
        client.deleteChildren(path)
        DistributedSemaphore(client, path, 2).use { semaphore ->
          val aAcquired = CountDownLatch(1)
          val aLost = CountDownLatch(1)
          var aReleased: Boolean? = null
          val a =
            thread {
              semaphore.acquire()
              aAcquired.countDown()
              aLost.await()
              aReleased = semaphore.release()
            }
          aAcquired.await(10, TimeUnit.SECONDS) shouldBe true
          val aEntry = leasedUnder(client, "$path/holders").single()
          val bAcquired = CountDownLatch(1)
          val bRelease = CountDownLatch(1)
          val b =
            thread {
              semaphore.acquire()
              bAcquired.countDown()
              bRelease.await()
              semaphore.release()
            }
          bAcquired.await(10, TimeUnit.SECONDS) shouldBe true
          // A's permit is lost (its lease revoked), then A releases
          client.leaseClient.revoke(client.getResponse(aEntry).kvs.single().lease).get()
          pollUntil(10.seconds) { !semaphore.holdsPermitAcquiredOn(a) } shouldBe true
          aLost.countDown()
          a.join(10_000)
          aReleased shouldBe false
          withClue("A's release gave up B's live permit") {
            semaphore.holdsPermitAcquiredOn(b) shouldBe true
            leasedUnder(client, "$path/holders") shouldHaveSize 1
          }
          bRelease.countDown()
          b.join(10_000)
        }
        client.deleteChildren(path)
      }
    }

    "lock and acquire declare InterruptedException for Java callers" {
      val lockMethods =
        listOf(
          DistributedMutex::class.java.getMethod("lock"),
          DistributedMutex::class.java.getMethod("tryLock", Long::class.java, TimeUnit::class.java),
          DistributedSemaphore::class.java.getMethod("acquire"),
          DistributedSemaphore::class.java.getMethod("tryAcquire", Long::class.java, TimeUnit::class.java),
        )
      for (method in lockMethods) {
        withClue(method.name) {
          method.exceptionTypes.toList().contains(InterruptedException::class.java) shouldBe true
        }
      }
    }

    "a tryLock or tryAcquire deadline bounds its RPCs too" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/deadline"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        DistributedMutex(client, path).use { mutex ->
          DistributedSemaphore(client, "$path/semaphore", 1).use { semaphore ->
            semaphore.availablePermits() // settle the semaphore's setup before the brownout
            client.hangLeaseGrants.store(true) // an etcd brownout: grants never answer
            var locked: Result<Boolean>? = null
            withClue("tryLock(500 ms) outlived its deadline") {
              finishesWithin(5) { locked = runCatching { mutex.tryLock(500.milliseconds) } } shouldBe true
            }
            withClue("tryLock threw ${locked!!.exceptionOrNull()}") { locked.getOrNull() shouldBe false }
            var acquired: Result<Boolean>? = null
            withClue("tryAcquire(500 ms) outlived its deadline") {
              finishesWithin(5) { acquired = runCatching { semaphore.tryAcquire(500.milliseconds) } } shouldBe true
            }
            acquired!!.getOrNull() shouldBe false
            client.hangLeaseGrants.store(false)
          }
        }
        etcd.deleteChildren(path)
      }
    }

    "a release survives one failed revoke" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/release-retry"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        DistributedSemaphore(client, path, 1, leaseTtlSecs = 30).use { semaphore ->
          semaphore.acquire()
          client.failLeaseRevokes.store(1)
          semaphore.release() shouldBe true
          withClue("one failed revoke left the entry for its 30 s TTL") {
            leasedUnder(etcd, "$path/holders").shouldBeEmpty()
          }
        }
        etcd.deleteChildren(path)
      }
    }
  }
}
