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
import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Lock-lost semantics, driven for real by revoking leases out-of-band (etcd
 * exposes each entry's lease id) — what a partition longer than the TTL produces.
 * Loss handling is cooperative: state flips, listener fires, LOST is reported;
 * interruption is opt-in.
 */
class MutexLockLostTests : StringSpec() {
  private val base = "/mutex/${javaClass.simpleName}"

  /** Revokes the lease behind the earliest (holder) entry under the lock prefix. */
  private fun revokeHolderLease(
    client: Client,
    lockPath: String,
  ) {
    val kvs = client.getResponse(lockPath, io.etcd.recipes.common.getOption { isPrefix(true) }).kvs
    (kvs.isNotEmpty()) shouldBe true
    val holder = kvs.minByOrNull { it.createRevision }
    holder.shouldNotBeNull()
    (holder.lease > 0L) shouldBe true
    client.leaseClient.revoke(holder.lease).get()
  }

  init {
    "holder observes lock loss when its lease is revoked" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/lost"
        val lost = CopyOnWriteArrayList<Throwable?>()

        DistributedMutex(client, path).use { mutex ->
          mutex.addLockLostListener { cause -> lost += cause }
          mutex.lock()
          mutex.isHeldByCurrentThread shouldBe true

          revokeHolderLease(client, path)

          pollUntil(15.seconds) { !mutex.isHeldByCurrentThread } shouldBe true
          pollUntil(10.seconds) { lost.size == 1 } shouldBe true
          mutex.connectionState shouldBe ConnectionState.LOST
          mutex.hasExceptions shouldBe true
          mutex.unlock() shouldBe false // dispossessed, not an error
        }
      }
    }

    "another instance acquires after the holder's lease is revoked" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/succession"
        connectToEtcd(urls) { client2 ->
          DistributedMutex(client, path).use { doomed ->
            DistributedMutex(client2, path).use { successor ->
              doomed.lock()
              revokeHolderLease(client, path)
              successor.tryLock(15.seconds) shouldBe true
              successor.unlock() shouldBe true
            }
          }
        }
      }
    }

    "interruptOnLockLoss interrupts a parked holder; the default does not" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)

        // Opt-in: the parked holder is interrupted
        val interruptedPath = "$base/interrupt-on"
        val wasInterrupted = BooleanMonitor(false)
        val heldOn = BooleanMonitor(false)
        DistributedMutex(client, interruptedPath, interruptOnLockLoss = true).use { mutex ->
          val holder =
            thread {
              mutex.lock()
              heldOn.set(true)
              try {
                Thread.sleep(60_000)
              } catch (e: InterruptedException) {
                wasInterrupted.set(true)
              }
            }
          heldOn.waitUntilTrue(15.seconds) shouldBe true
          revokeHolderLease(client, interruptedPath)
          wasInterrupted.waitUntilTrue(15.seconds) shouldBe true
          holder.join(10_000)
        }

        // Default: cooperative only — no interrupt lands
        val cooperativePath = "$base/interrupt-off"
        val interrupted = AtomicBoolean(false)
        val heldOff = BooleanMonitor(false)
        val finished = BooleanMonitor(false)
        DistributedMutex(client, cooperativePath).use { mutex ->
          val holder =
            thread {
              mutex.lock()
              heldOff.set(true)
              try {
                Thread.sleep(4_000)
              } catch (e: InterruptedException) {
                interrupted.set(true)
              }
              finished.set(true)
            }
          heldOff.waitUntilTrue(15.seconds) shouldBe true
          revokeHolderLease(client, cooperativePath)
          finished.waitUntilTrue(20.seconds) shouldBe true
          interrupted.get() shouldBe false
          pollUntil(10.seconds) { !mutex.isHeldByCurrentThread } shouldBe true
          holder.join(10_000)
        }
      }
    }

    "a waiter whose lease is revoked mid-wait re-queues and still acquires" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/waiter-revoked"
        connectToEtcd(urls) { client2 ->
          DistributedMutex(client, path).use { holder ->
            DistributedMutex(client2, path).use { waiter ->
              holder.lock()

              val acquired = BooleanMonitor(false)
              val releaseWaiter = BooleanMonitor(false)
              val waiterThread =
                thread {
                  waiter.lock()
                  acquired.set(true)
                  releaseWaiter.waitUntilTrue() // holds are thread-owned: unlock here
                  waiter.unlock()
                }
              // The waiter's queue entry is the SECOND entry by create revision
              pollUntil(15.seconds) {
                client.getResponse(path, io.etcd.recipes.common.getOption { isPrefix(true) }).kvs.size == 2
              } shouldBe true
              val waiterKv =
                client.getResponse(path, io.etcd.recipes.common.getOption { isPrefix(true) })
                  .kvs.maxByOrNull { it.createRevision }!!
              client.leaseClient.revoke(waiterKv.lease).get()

              // The waiter recovers with a fresh lease and re-queues
              pollUntil(20.seconds) {
                client.getResponse(path, io.etcd.recipes.common.getOption { isPrefix(true) }).kvs.size == 2
              } shouldBe true

              holder.unlock() shouldBe true
              acquired.waitUntilTrue(20.seconds) shouldBe true
              releaseWaiter.set(true)
              waiterThread.join(10_000)
            }
          }
        }
      }
    }
  }
}
