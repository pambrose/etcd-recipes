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
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getChildCount
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
 * Permit-lost semantics driven by revoking entry leases out-of-band: a lost
 * holder frees capacity and fires the listener, and a lost waiter re-queues
 * with a fresh entry.
 */
class SemaphorePermitLostTests : StringSpec() {
  private val base = "/semaphore/${javaClass.simpleName}"

  private fun revokeLease(
    client: Client,
    holdersPath: String,
    newest: Boolean,
  ) {
    val kvs = client.getResponse(holdersPath, getOption { isPrefix(true) }).kvs
    val entry =
      if (newest) kvs.maxByOrNull { it.createRevision }!! else kvs.minByOrNull { it.createRevision }!!
    (entry.lease > 0L) shouldBe true
    client.leaseClient.revoke(entry.lease).get()
  }

  init {
    "a lost permit frees capacity, fires the listener, and release returns false" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/holder-lost"
        val holdersPath = "$path/holders"
        val lost = CopyOnWriteArrayList<Throwable?>()

        connectToEtcd(urls) { client2 ->
          DistributedSemaphore(client, path, 1).use { doomed ->
            DistributedSemaphore(client2, path, 1).use { successor ->
              doomed.addPermitLostListener { cause -> lost += cause }
              doomed.acquire()

              val acquired = BooleanMonitor(false)
              val waiter =
                thread {
                  successor.acquire()
                  acquired.set(true)
                  successor.release()
                }
              pollUntil(15.seconds) { client.getChildCount(holdersPath) == 2L } shouldBe true
              acquired.get() shouldBe false

              revokeLease(client, holdersPath, newest = false)

              acquired.waitUntilTrue(20.seconds) shouldBe true
              pollUntil(10.seconds) { lost.size == 1 } shouldBe true
              doomed.release() shouldBe false
              waiter.join(10_000)
            }
          }
        }
      }
    }

    "a waiter revoked mid-wait re-queues and still acquires" {
      connectToEtcd(urls) { client ->
        client.deleteChildren(base)
        val path = "$base/waiter-revoked"
        val holdersPath = "$path/holders"

        connectToEtcd(urls) { client2 ->
          DistributedSemaphore(client, path, 1).use { holder ->
            DistributedSemaphore(client2, path, 1).use { waiterSide ->
              holder.acquire()

              val acquired = BooleanMonitor(false)
              val waiter =
                thread {
                  waiterSide.acquire()
                  acquired.set(true)
                  waiterSide.release()
                }
              pollUntil(15.seconds) { client.getChildCount(holdersPath) == 2L } shouldBe true

              // Kill the WAITER's entry lease (the later create revision)
              revokeLease(client, holdersPath, newest = true)

              // The waiter recovers: a fresh entry appears
              pollUntil(20.seconds) { client.getChildCount(holdersPath) == 2L } shouldBe true

              holder.release() shouldBe true
              acquired.waitUntilTrue(30.seconds) shouldBe true
              waiter.join(10_000)
            }
          }
        }
      }
    }
  }
}
