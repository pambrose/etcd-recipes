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

import com.pambrose.common.concurrent.BooleanMonitor
import io.etcd.jetcd.Client
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Lock-lost semantics for the read-write lock, driven by revoking entry leases
 * out-of-band: a lost holder unblocks its queued conflicts, and a lost waiter
 * re-queues with a fresh entry.
 */
class ReadWriteLockLostTests : StringSpec() {
  private val base = "/rwlock/${javaClass.simpleName}"

  private fun revokeEntryLease(
    client: Client,
    lockPath: String,
    basenamePrefix: String,
  ) {
    val kvs = client.getResponse(lockPath, getOption { isPrefix(true) }).kvs
    val entry = kvs.first { it.key.asString.substringAfterLast('/').startsWith(basenamePrefix) }
    (entry.lease > 0L) shouldBe true
    client.leaseClient.revoke(entry.lease).get()
  }

  init {
    "a lost writer hold unblocks queued readers and fires the listener" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/writer-lost"
        val lost = CopyOnWriteArrayList<Throwable?>()

        connectToEtcd(urls) { client2 ->
          DistributedReadWriteLock(client, path).use { doomed ->
            DistributedReadWriteLock(client2, path).use { readerSide ->
              doomed.writeLock.addLockLostListener { cause -> lost += cause }
              val held = BooleanMonitor(false)
              val writerThread =
                thread {
                  doomed.writeLock.lock()
                  held.set(true)
                  Thread.sleep(30_000)
                }
              held.waitUntilTrue(15.seconds) shouldBe true

              val readerAcquired = BooleanMonitor(false)
              val readerThread =
                thread {
                  readerSide.readLock.lock()
                  readerAcquired.set(true)
                  readerSide.readLock.unlock()
                }
              // reader queued behind the writer
              pollUntil(15.seconds) {
                client.getResponse(path, getOption { isPrefix(true) }).kvs.size == 2
              } shouldBe true
              readerAcquired.get() shouldBe false

              revokeEntryLease(client, path, "write-")

              readerAcquired.waitUntilTrue(20.seconds) shouldBe true
              pollUntil(10.seconds) { lost.size == 1 } shouldBe true
              writerThread.interrupt()
              writerThread.join(10_000)
              readerThread.join(10_000)
            }
          }
        }
      }
    }

    "a waiter revoked mid-wait re-queues and still acquires" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/waiter-revoked"
        connectToEtcd(urls) { client2 ->
          DistributedReadWriteLock(client, path).use { holder ->
            DistributedReadWriteLock(client2, path).use { waiterSide ->
              val release = BooleanMonitor(false)
              val held = BooleanMonitor(false)
              val holderThread =
                thread {
                  holder.writeLock.lock()
                  held.set(true)
                  release.waitUntilTrue()
                  holder.writeLock.unlock()
                }
              held.waitUntilTrue(15.seconds) shouldBe true

              val acquired = BooleanMonitor(false)
              val waiterThread =
                thread {
                  waiterSide.writeLock.lock()
                  acquired.set(true)
                  waiterSide.writeLock.unlock()
                }
              pollUntil(15.seconds) {
                client.getResponse(path, getOption { isPrefix(true) }).kvs.size == 2
              } shouldBe true

              // Kill the WAITER's entry lease (the later create revision)
              val waiterKv =
                client.getResponse(path, getOption { isPrefix(true) })
                  .kvs.maxByOrNull { it.createRevision }!!
              client.leaseClient.revoke(waiterKv.lease).get()

              // The waiter recovers: a fresh entry appears
              pollUntil(20.seconds) {
                client.getResponse(path, getOption { isPrefix(true) }).kvs.size == 2
              } shouldBe true

              release.set(true)
              acquired.waitUntilTrue(30.seconds) shouldBe true
              holderThread.join(10_000)
              waiterThread.join(10_000)
            }
          }
        }
      }
    }
  }
}
