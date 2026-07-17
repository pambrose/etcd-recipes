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

package io.etcd.recipes.coroutines

import io.etcd.jetcd.Client
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.urls
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.lock.DistributedMutex
import io.etcd.recipes.lock.DistributedSemaphore
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Loss-driven flows exercised deterministically by revoking the backing lease
 * out-of-band: lock-lost, permit-lost, and lease-event surfaces.
 */
class LossFlowTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  private suspend fun <T> withScope(body: suspend (CoroutineScope) -> T): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      return body(scope)
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }

  private fun revokeLeaseUnder(
    client: Client,
    prefix: String,
  ) {
    val kv = client.getResponse(prefix, getOption { isPrefix(true) }).kvs.first { it.lease > 0L }
    client.leaseClient.revoke(kv.lease).get()
  }

  init {
    "lockLostAsFlow emits when the held lock's lease is revoked" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/lock"
        val lost = CopyOnWriteArrayList<LockLostEvent>()

        DistributedMutex(client, path).use { mutex ->
          val held = java.util.concurrent.CountDownLatch(1)
          val holder =
            thread {
              mutex.lock()
              held.countDown()
              Thread.sleep(30_000)
            }
          held.await()

          withScope { scope ->
            scope.launch { mutex.lockLostAsFlow().collect { lost += it } }
            delay(1_000)

            revokeLeaseUnder(client, path)

            untilTrue(20.seconds) { lost.size == 1 } shouldBe true
          }
          holder.interrupt()
          holder.join(10_000)
        }
      }
    }

    "permitLostAsFlow emits when a held permit's lease is revoked" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/semaphore"
        val lost = CopyOnWriteArrayList<PermitLostEvent>()

        DistributedSemaphore(client, path, 1).use { semaphore ->
          semaphore.acquire()
          withScope { scope ->
            scope.launch { semaphore.permitLostAsFlow().collect { lost += it } }
            delay(1_000)

            revokeLeaseUnder(client, "$path/holders")

            untilTrue(20.seconds) { lost.size == 1 } shouldBe true
          }
        }
      }
    }

    "leaseEventsAsFlow reports an Expired event when the key's lease is revoked" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/tkv"
        val events = CopyOnWriteArrayList<LeaseEvent>()

        TransientKeyValue(client, path, "alive", leaseTtlSecs = 2).use { tkv ->
          withScope { scope ->
            scope.launch { tkv.leaseEventsAsFlow().collect { events += it } }
            delay(1_000)

            // Revoke the key's lease; the self-healer surfaces the loss then re-registers
            val kv = client.getResponse(path).kvs.first()
            client.leaseClient.revoke(kv.lease).get()

            untilTrue(20.seconds) {
              events.any { it is LeaseEvent.Expired || it is LeaseEvent.Restored }
            } shouldBe true
          }
        }
      }
    }

    "the permit-lost flow unregisters on cancel and fires nothing spuriously afterward" {
      connectToEtcd(urls).use { client ->
        client.deleteChildren(base)
        val path = "$base/unregister"
        val lost = CopyOnWriteArrayList<PermitLostEvent>()

        DistributedSemaphore(client, path, 1).use { semaphore ->
          semaphore.acquire()
          withScope { scope ->
            val job = scope.launch { semaphore.permitLostAsFlow().collect { lost += it } }
            delay(1_000)
            job.cancelAndJoin()

            // After the collector is gone, a real loss must not reach the drained list
            revokeLeaseUnder(client, "$path/holders")
            delay(3_000)
            lost.size shouldBe 0
          }
          semaphore.release() shouldBe false // the permit was lost out-of-band
        }
      }
    }
  }
}
