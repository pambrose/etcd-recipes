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

package io.etcd.recipes.election

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch

// Regression test for: LeaderSelector with the default internal executor
// could not be re-used after close() because close() shut down the
// ExecutorService and a subsequent start() would dispatch onto a terminated pool.
class LeaderSelectorReuseTests : StringSpec() {
  init {
    "startWorksAfterCloseWithInternalExecutor" {
      val path = "/election/LeaderSelectorReuseTests"
      val tookLeadership = AtomicInt(0)

      connectToEtcd(urls) { client ->
        // No userExecutor — the LeaderSelector creates its own ExecutorService.
        val selector =
          LeaderSelector(
            client,
            path,
            takeLeadershipBlock = { tookLeadership.incrementAndFetch() },
          )

        // First cycle.
        selector.start()
        selector.waitOnLeadershipComplete()
        selector.close()

        // Bug: this second start() previously threw RejectedExecutionException
        // because close() had shut down the internal executor.
        selector.start()
        selector.waitOnLeadershipComplete()
        selector.close()

        tookLeadership.load() shouldBe 2
        selector.hasExceptions shouldBe false
      }
    }

    // Symmetric to the internal-executor case: a user-supplied executor must
    // survive close() so the same instance can be re-used, and close() must
    // NOT shut down the user's executor (it didn't create it).
    "startWorksAfterCloseWithUserExecutor" {
      val path = "/election/LeaderSelectorReuseTests-userExecutor"
      val tookLeadership = AtomicInt(0)
      val userExecutor = Executors.newFixedThreadPool(3)

      try {
        connectToEtcd(urls) { client ->
          val selector =
            LeaderSelector(
              client,
              path,
              takeLeadershipBlock = { tookLeadership.incrementAndFetch() },
              executorService = userExecutor,
            )

          selector.start()
          selector.waitOnLeadershipComplete()
          selector.close()

          // The user's executor must remain usable across close().
          userExecutor.isShutdown shouldBe false

          selector.start()
          selector.waitOnLeadershipComplete()
          selector.close()

          tookLeadership.load() shouldBe 2
          selector.hasExceptions shouldBe false
          userExecutor.isShutdown shouldBe false
        }
      } finally {
        userExecutor.shutdown()
        userExecutor.awaitTermination(5, TimeUnit.SECONDS)
      }
    }
  }
}
