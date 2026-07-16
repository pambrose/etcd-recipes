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

package io.etcd.recipes.fault

import com.pambrose.common.concurrent.BooleanMonitor
import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import io.etcd.recipes.lock.DistributedSemaphore
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Real partition: a permit holder paused longer than its lease TTL loses the
 * permit, and a queued waiter acquires once the cluster is reachable again.
 */
class SemaphoreFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "a partitioned holder loses its permit and a queued waiter acquires" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        val semPath = "$path/partition"
        connectToEtcd(urls) { client2 ->
          DistributedSemaphore(client, semPath, 1, leaseTtlSecs = 2).use { doomed ->
            DistributedSemaphore(client2, semPath, 1, leaseTtlSecs = 2).use { successor ->
              doomed.acquire()

              val acquired = BooleanMonitor(false)
              val waiter =
                thread {
                  successor.acquire()
                  acquired.set(true)
                  successor.release()
                }
              Thread.sleep(1_000) // let the waiter queue behind the holder

              EtcdTestContainer.pause()
              try {
                Thread.sleep(7_000) // well past the 2s TTL
              } finally {
                EtcdTestContainer.unpause()
              }
              EtcdTestContainer.awaitReady()

              acquired.waitUntilTrue(60.seconds) shouldBe true
              waiter.join(10_000)
            }
          }
        }
      }
    }
  }
}
