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

import io.etcd.recipes.common.ConnectionState
import io.etcd.recipes.common.EtcdTestContainer
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.urls
import io.etcd.recipes.coroutines.connectionStateAsFlow
import io.etcd.recipes.coroutines.leaseEventsAsFlow
import io.etcd.recipes.keyvalue.TransientKeyValue
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Flow event surfaces under a real partition: a paused etcd drives the lease and
 * connection-state flows through their loss/recovery transitions.
 */
class EventFlowFaultTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  private suspend fun untilTrue(
    timeout: Duration,
    predicate: () -> Boolean,
  ): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < timeout) {
      if (predicate()) return true
      delay(50)
    }
    return predicate()
  }

  init {
    "a partition drives the lease and connection-state flows through loss and recovery" {
      assumeFaultInjection()
      connectToEtcd(urls).use { client ->
        client.deleteChildren(path)
        val leaseEvents = CopyOnWriteArrayList<LeaseEvent>()
        val states = CopyOnWriteArrayList<ConnectionState>()

        TransientKeyValue(client, "$path/kv", "alive", leaseTtlSecs = 2).use { tkv ->
          val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
          try {
            scope.launch { tkv.leaseEventsAsFlow().collect { leaseEvents += it } }
            scope.launch { tkv.connectionStateAsFlow().collect { states += it } }
            delay(1_000)

            EtcdTestContainer.pause()
            try {
              delay(7_000) // well past the 2s TTL
            } finally {
              EtcdTestContainer.unpause()
            }
            EtcdTestContainer.awaitReady()

            // The lease was lost during the partition and the healer restores it
            untilTrue(60.seconds) {
              leaseEvents.any { it is LeaseEvent.Expired || it is LeaseEvent.Restored }
            } shouldBe true
            // Connection state left CONNECTED during the outage
            untilTrue(30.seconds) {
              states.any { it != ConnectionState.CONNECTED }
            } shouldBe true
          } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
          }
        }
      }
    }
  }
}
