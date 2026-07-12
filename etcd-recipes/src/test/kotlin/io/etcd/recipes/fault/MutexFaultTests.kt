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

import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.etcd.recipes.lock.DistributedMutex
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Real partition: a lock holder paused longer than its lease TTL loses the lock,
 * and another instance acquires once the cluster is reachable again.
 */
class MutexFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "a partitioned holder loses the lock and another instance acquires" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        val lockPath = "$path/partition"
        val lost = CopyOnWriteArrayList<Throwable?>()

        connectToEtcd(urls) { client2 ->
          DistributedMutex(client, lockPath, leaseTtlSecs = 2).use { holder ->
            DistributedMutex(client2, lockPath, leaseTtlSecs = 2).use { successor ->
              holder.addLockLostListener { cause -> lost += cause }
              holder.lock()

              EtcdTestContainer.pause()
              try {
                Thread.sleep(7_000) // well past the 2s TTL
              } finally {
                EtcdTestContainer.unpause()
              }
              EtcdTestContainer.awaitReady()

              pollUntil(60.seconds) { !holder.isHeldByCurrentThread } shouldBe true
              pollUntil(30.seconds) { lost.size == 1 } shouldBe true
              successor.tryLock(30.seconds) shouldBe true
              successor.unlock() shouldBe true
              holder.unlock() shouldBe false
            }
          }
        }
      }
    }
  }
}
