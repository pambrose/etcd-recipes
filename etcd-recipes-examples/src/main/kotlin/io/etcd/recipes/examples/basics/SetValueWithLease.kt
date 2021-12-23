/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.concurrent.thread
import com.github.pambrose.common.util.repeatWithSleep
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.putValue
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

fun main() {
  val urls = listOf("http://localhost:2379")
  val path = "/foo"
  val keyval = "foobar"
  val latch = CountDownLatch(2)

  thread(latch) {
    sleep(3.seconds)
    connectToEtcd(urls) { client ->
      println("Assigning $path = $keyval")
      val lease = client.leaseGrant(5.seconds)
      client.putValue(path, keyval, putOption { withLeaseId(lease.id) })
    }
  }

  thread(latch) {
    connectToEtcd(urls) { client ->
      repeatWithSleep(12) { _, start ->
        val kval = client.getValue(path, "unset")
        println("Key $path = $kval after ${System.currentTimeMillis() - start}ms")
      }
    }
  }

  latch.await()
}