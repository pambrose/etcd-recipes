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
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.etcd.recipes.lock.DistributedReadWriteLock
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Real partition: a writer paused longer than its lease TTL loses the write lock,
 * and queued readers acquire once the cluster is reachable again.
 */
class ReadWriteLockFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "a partitioned writer loses the lock and queued readers acquire" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        val lockPath = "$path/partition"
        connectToEtcd(urls) { client2 ->
          DistributedReadWriteLock(client, lockPath, leaseTtlSecs = 2).use { doomed ->
            DistributedReadWriteLock(client2, lockPath, leaseTtlSecs = 2).use { readerSide ->
              val held = BooleanMonitor(false)
              val writerThread =
                thread {
                  doomed.writeLock.lock()
                  held.set(true)
                  Thread.sleep(60_000)
                }
              held.waitUntilTrue(15.seconds) shouldBe true

              val readerAcquired = BooleanMonitor(false)
              val readerThread =
                thread {
                  readerSide.readLock.lock()
                  readerAcquired.set(true)
                  readerSide.readLock.unlock()
                }
              Thread.sleep(1_000) // let the reader queue behind the writer

              EtcdTestContainer.pause()
              try {
                Thread.sleep(7_000) // well past the 2s TTL
              } finally {
                EtcdTestContainer.unpause()
              }
              EtcdTestContainer.awaitReady()

              readerAcquired.waitUntilTrue(60.seconds) shouldBe true
              pollUntil(30.seconds) { !doomed.writeLock.isHeldByCurrentThread } shouldBe true
              writerThread.interrupt()
              writerThread.join(10_000)
              readerThread.join(10_000)
            }
          }
        }
      }
    }
  }
}
