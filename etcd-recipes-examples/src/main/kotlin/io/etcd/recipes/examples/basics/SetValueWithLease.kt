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

package io.etcd.recipes.examples.basics

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.putValue
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

fun main() {
  val logger = KotlinLogging.logger {}
  val urls = ["http://localhost:2379"]
  val path = "/foo"
  val keyval = "foobar"
  val latch = CountDownLatch(2)

  thread {
    try {
      Thread.sleep(3_000)
      connectToEtcd(urls) { client ->
        logger.info {"Assigning $path = $keyval"}
        val lease = client.leaseGrant(5.seconds)
        client.putValue(path, keyval, putOption { withLeaseId(lease.id) })
      }
    } finally {
      latch.countDown()
    }
  }

  thread {
    try {
      connectToEtcd(urls) { client ->
        val start = System.currentTimeMillis()
        repeat(12) { i ->
          if (i > 0) Thread.sleep(1_000)
          val kval = client.getValue(path, "unset")
          logger.info {"Key $path = $kval after ${System.currentTimeMillis() - start}ms"}
        }
      }
    } finally {
      latch.countDown()
    }
  }

  latch.await()
}
