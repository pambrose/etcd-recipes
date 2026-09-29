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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Correctness of the read-write lock's conflict scan:
 *
 * - a lock only sees its own entries, not those of a sibling lock whose path merely
 *   shares its string prefix (`/order-1` vs `/order-10`);
 * - entries are classified by their name under the lock path, so a `clientId`
 *   containing `/` cannot make a writer invisible to readers;
 * - a write→read downgrade keeps the write entry's place in line, so it neither
 *   deadlocks behind a writer queued after it nor lets that writer in while the
 *   downgraded reader still holds.
 */
class ReadWriteLockCorrectnessTests : StringSpec() {
  private val base = "/locks/${javaClass.simpleName}"

  init {
    "a lock ignores the entries of a sibling lock whose path shares its prefix" {
      connectToEtcd(urls) { client ->
        val path = "$base/prefix"
        client.deleteChildren(path)

        DistributedReadWriteLock(client, "$path/order-10").use { order10 ->
          DistributedReadWriteLock(client, "$path/order-1").use { order1 ->
            order10.writeLock.lock()
            try {
              // Unrelated locks: holding /order-10 must not block /order-1.
              withClue("/order-1 treated /order-10's entry as a conflict") {
                order1.writeLock.tryLock(3.seconds) shouldBe true
              }
              order1.writeLock.unlock()
            } finally {
              order10.writeLock.unlock()
            }
          }
        }
        client.deleteChildren(path)
      }
    }

    "a writer whose clientId contains a slash still excludes readers" {
      connectToEtcd(urls) { client ->
        val path = "$base/slash-client-id"
        client.deleteChildren(path)

        DistributedReadWriteLock(client, path, clientId = "orders/pod-7").use { writer ->
          DistributedReadWriteLock(client, path).use { reader ->
            writer.writeLock.lock()
            try {
              writer.writeLock.isLocked shouldBe true
              val admitted = reader.readLock.tryLock(2.seconds)
              if (admitted) reader.readLock.unlock()
              withClue("a reader was admitted while the writer held the lock") { admitted shouldBe false }
            } finally {
              writer.writeLock.unlock()
            }
          }
        }
        client.deleteChildren(path)
      }
    }

    "a downgrade keeps its place ahead of a writer that queued behind the write hold" {
      connectToEtcd(urls) { client ->
        val path = "$base/downgrade-queued-writer"
        client.deleteChildren(path)

        DistributedReadWriteLock(client, path).use { a ->
          DistributedReadWriteLock(client, path).use { b ->
            a.writeLock.lock()

            val bAcquired = CountDownLatch(1)
            val bReleased = CountDownLatch(1)
            thread(isDaemon = true) {
              b.writeLock.lock()
              bAcquired.countDown()
              b.writeLock.unlock()
              bReleased.countDown()
            }
            // B's write entry is queued behind A's
            pollUntil(10.seconds) { client.getChildCount(path) == 2L } shouldBe true

            try {
              // Downgrade: take the read side while holding write, then drop write
              withClue("the downgrade waited on the writer queued behind it") {
                a.readLock.tryLock(5.seconds) shouldBe true
              }
            } finally {
              a.writeLock.unlock()
            }

            withClue("the queued writer got in while the downgraded reader still held") {
              bAcquired.await(1, TimeUnit.SECONDS) shouldBe false
            }
            a.readLock.unlock()

            withClue("the queued writer never acquired after the downgraded read was released") {
              bAcquired.await(10, TimeUnit.SECONDS) shouldBe true
            }
            bReleased.await(10, TimeUnit.SECONDS) shouldBe true
          }
        }
        client.deleteChildren(path)
      }
    }

    "a clientId that could be mistaken for a carried rank is rejected" {
      connectToEtcd(urls) { client ->
        shouldThrow<IllegalArgumentException> {
          DistributedReadWriteLock(client, "$base/rank-client-id", clientId = "rank:5")
        }
      }
    }

    "a downgrade does not inherit the place of a write entry that is already gone" {
      connectToEtcd(urls) { client ->
        val path = "$base/downgrade-lost-write"
        client.deleteChildren(path)

        DistributedReadWriteLock(client, path, clientId = "A").use { a ->
          DistributedReadWriteLock(client, path, clientId = "B").use { b ->
            a.writeLock.lock()

            val bAcquired = CountDownLatch(1)
            val bRelease = CountDownLatch(1)
            thread(isDaemon = true) {
              b.writeLock.lock()
              bAcquired.countDown()
              bRelease.await()
              b.writeLock.unlock()
            }
            pollUntil(10.seconds) { client.getChildCount(path) == 2L } shouldBe true

            // A's write entry vanishes server-side (as on a lease expiry the client has
            // not noticed yet), so B is admitted and now holds the write lock.
            client.deleteKey(client.getChildrenKeys(path).single { it.contains("/write-A:") })
            bAcquired.await(10, TimeUnit.SECONDS) shouldBe true

            try {
              val admitted = a.readLock.tryLock(2.seconds)
              if (admitted) a.readLock.unlock()
              withClue("a downgrade from a vanished write was admitted while B held the write lock") {
                admitted shouldBe false
              }
            } finally {
              bRelease.countDown()
              a.writeLock.unlock()
            }
          }
        }
        client.deleteChildren(path)
      }
    }
  }
}
