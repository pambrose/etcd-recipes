/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.barrier

import com.github.pambrose.common.util.random
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.nonblockingThreads
import io.etcd.recipes.common.urls
import kotlinx.atomicfu.atomic
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.time.seconds

class DistributedBarrierWithCountTests {

  @Test
  fun badArgsTest() {
    connectToEtcd(urls) { client ->
      invoking { DistributedBarrierWithCount(client, "something", 0) } shouldThrow IllegalArgumentException::class
      invoking { DistributedBarrierWithCount(client, "", 1) } shouldThrow IllegalArgumentException::class
    }
  }

  @Test
  fun barrierWithCountTest() {
    val path = "/barriers/${javaClass.simpleName}"
    val count = 30
    val retryAttempts = 5
    val retryLatch = CountDownLatch(count - 1)
    val retryCounter = atomic(0)
    val advancedCounter = atomic(0)

    fun waiter(id: Int, barrier: DistributedBarrierWithCount, retryCount: Int = 0) {

      sleep(5.random().seconds)
      logger.debug { "#$id Waiting on barrier" }

      repeat(retryCount) {
        barrier.waitOnBarrier(1.seconds)
        logger.debug { "#$id Timed out waiting on barrier, waiting again" }
        retryCounter.incrementAndGet()
      }

      retryLatch.countDown()

      logger.debug { "#$id Waiter count = ${barrier.waiterCount}" }
      barrier.waitOnBarrier()

      advancedCounter.incrementAndGet()

      logger.debug { "#$id Done waiting on barrier" }
    }

    connectToEtcd(urls) { client ->

      client.deleteChildren(path)

      val (finishedLatch, holder) =
        nonblockingThreads(count - 1) { i ->
          connectToEtcd(urls) { client ->
            withDistributedBarrierWithCount(client, path, count) {
              waiter(i, this, retryAttempts)
            }
          }
        }

      retryLatch.await()
      sleep(2.seconds)

      withDistributedBarrierWithCount(client, path, count) {
        waiter(99, this)
      }

      finishedLatch.await()

      holder.checkForException()
    }

    retryCounter.value shouldBeEqualTo retryAttempts * (count - 1)
    advancedCounter.value shouldBeEqualTo count

    logger.debug { "Done" }
  }

  companion object : KLogging()
}