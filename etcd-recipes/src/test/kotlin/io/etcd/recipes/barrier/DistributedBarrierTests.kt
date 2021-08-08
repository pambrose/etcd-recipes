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

package io.etcd.recipes.barrier

import com.github.pambrose.common.concurrent.thread
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.*
import mu.KLogging
import org.amshove.kluent.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

class DistributedBarrierTests {

  @Test
  fun badArgsTest() {
    connectToEtcd(urls) { client ->
      invoking { DistributedBarrier(client, "") } shouldThrow IllegalArgumentException::class
    }
  }

  @Test
  fun barrierTest() {
    val path = "/barriers/${javaClass.simpleName}"
    val count = 10
    val setBarrierLatch = CountDownLatch(1)
    val completeLatch = CountDownLatch(1)
    val removeBarrierTime = AtomicLong(0L)
    val timeoutCount = AtomicInteger(0)
    val advancedCount = AtomicInteger(0)

    connectToEtcd(urls) { client ->

      thread(completeLatch) {

        withDistributedBarrier(client, path) {

          isBarrierSet() shouldBeEqualTo false

          logger.debug { "Setting Barrier" }
          val isSet = setBarrier()
          isSet.shouldBeTrue()
          isBarrierSet().shouldBeTrue()
          setBarrierLatch.countDown()

          // This should return false because barrier is already set
          val isSet2 = setBarrier()
          isSet2.shouldBeFalse()

          // Pause to give time-outs a chance
          sleep(Duration.seconds(6))

          logger.debug { "Removing Barrier" }
          removeBarrierTime.set(System.currentTimeMillis())
          val isRemoved = removeBarrier()
          isRemoved.shouldBeTrue()

          // This should return false because remove already called
          val isRemoved2 = removeBarrier()
          isRemoved2.shouldBeFalse()

          sleep(Duration.seconds(3))
        }
      }

      blockingThreads(count) { i ->
        setBarrierLatch.await()
        withDistributedBarrier(client, path) {
          logger.debug { "$i Waiting on Barrier" }
          waitOnBarrier(Duration.seconds(1))

          timeoutCount.incrementAndGet()

          logger.debug { "$i Timed out waiting on barrier, waiting again" }
          waitOnBarrier()

          // Make sure the waiter advanced quickly
          System.currentTimeMillis() - removeBarrierTime.get() shouldBeLessThan 500
          advancedCount.incrementAndGet()

          logger.debug { "$i Done Waiting on Barrier" }
        }
      }
    }

    completeLatch.await()

    timeoutCount.get() shouldBeEqualTo count
    advancedCount.get() shouldBeEqualTo count

    logger.debug { "Done" }
  }

  @Test
  fun earlySetBarrierTest() {
    val path = "/barriers/early${javaClass.simpleName}"
    val count = 10
    val removeBarrierTime = AtomicLong(0L)
    val timeoutCount = AtomicInteger(0)
    val advancedCount = AtomicInteger(0)

    connectToEtcd(urls) { client ->

      val (finishedLatch, holder) =
        nonblockingThreads(count) { i ->
          withDistributedBarrier(client, path) {
            logger.debug { "$i Waiting on Barrier" }
            waitOnBarrier(1, TimeUnit.SECONDS)

            timeoutCount.incrementAndGet()

            logger.debug { "$i Timed out waiting on barrier, waiting again" }
            waitOnBarrier()

            // Make sure the waiter advanced quickly
            System.currentTimeMillis() - removeBarrierTime.get() shouldBeLessThan 500
            advancedCount.incrementAndGet()

            logger.debug { "$i Done Waiting on Barrier" }
          }
        }

      sleep(Duration.seconds(5))

      withDistributedBarrier(client, path) {

        isBarrierSet() shouldBeEqualTo false

        logger.debug { "Setting Barrier" }
        val isSet = setBarrier()
        isSet.shouldBeTrue()
        isBarrierSet().shouldBeTrue()

        // This sould return false because barrier is already set
        val isSet2 = setBarrier()
        isSet2.shouldBeFalse()

        // Pause to give time-outs a chance
        sleep(Duration.seconds(6))

        logger.debug { "Removing Barrier" }
        removeBarrierTime.set(System.currentTimeMillis())
        removeBarrier()

        sleep(Duration.seconds(3))
      }

      finishedLatch.await()

      holder.checkForException()
    }

    timeoutCount.get() shouldBeEqualTo count
    advancedCount.get() shouldBeEqualTo count

    logger.debug { "Done" }
  }

  companion object : KLogging()
}