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

import com.github.pambrose.common.concurrent.thread
import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.blockingThreads
import io.etcd.recipes.common.checkForException
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.nonblockingThreads
import io.etcd.recipes.common.urls
import kotlinx.atomicfu.atomic
import mu.KLogging
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeLessThan
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.seconds

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
    val removeBarrierTime = atomic(0L)
    val timeoutCount = atomic(0)
    val advancedCount = atomic(0)

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
          sleep(6.seconds)

          logger.debug { "Removing Barrier" }
          removeBarrierTime.value = System.currentTimeMillis()
          val isRemoved = removeBarrier()
          isRemoved.shouldBeTrue()

          // This should return false because remove already called
          val isRemoved2 = removeBarrier()
          isRemoved2.shouldBeFalse()

          sleep(3.seconds)
        }
      }

      blockingThreads(count) { i ->
        setBarrierLatch.await()
        withDistributedBarrier(client, path) {
          logger.debug { "$i Waiting on Barrier" }
          waitOnBarrier(1.seconds)

          timeoutCount.incrementAndGet()

          logger.debug { "$i Timed out waiting on barrier, waiting again" }
          waitOnBarrier()

          // Make sure the waiter advanced quickly
          System.currentTimeMillis() - removeBarrierTime.value shouldBeLessThan 500
          advancedCount.incrementAndGet()

          logger.debug { "$i Done Waiting on Barrier" }
        }
      }
    }

    completeLatch.await()

    timeoutCount.value shouldBeEqualTo count
    advancedCount.value shouldBeEqualTo count

    logger.debug { "Done" }
  }

  @Test
  fun earlySetBarrierTest() {
    val path = "/barriers/early${javaClass.simpleName}"
    val count = 10
    val removeBarrierTime = atomic(0L)
    val timeoutCount = atomic(0)
    val advancedCount = atomic(0)

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
            System.currentTimeMillis() - removeBarrierTime.value shouldBeLessThan 500
            advancedCount.incrementAndGet()

            logger.debug { "$i Done Waiting on Barrier" }
          }
        }

      sleep(5.seconds)

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
        sleep(6.seconds)

        logger.debug { "Removing Barrier" }
        removeBarrierTime.value = System.currentTimeMillis()
        removeBarrier()

        sleep(3.seconds)
      }

      finishedLatch.await()

      holder.checkForException()
    }

    timeoutCount.value shouldBeEqualTo count
    advancedCount.value shouldBeEqualTo count

    logger.debug { "Done" }
  }

  companion object : KLogging()
}