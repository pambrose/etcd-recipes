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

package io.etcd.recipes.coroutines

import io.etcd.jetcd.Client
import io.etcd.recipes.common.BackgroundException
import io.etcd.recipes.common.EtcdConnector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic coverage of [backgroundExceptionsAsFlow]: recorded background exceptions
 * reach a collector with their context, and cancelling the collector unregisters the
 * listener. No etcd needed — the recipe's own `recordException` is driven directly.
 */
class BackgroundExceptionFlowTests : StringSpec() {
  private class TestConnector(
    client: Client,
  ) : EtcdConnector(client) {
    override val exceptionContext get() = "test-recipe"

    fun record(t: Throwable) = recordException(t)
  }

  private suspend fun <T> withScope(body: suspend (CoroutineScope) -> T): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      return body(scope)
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }

  init {
    "backgroundExceptionsAsFlow emits recorded exceptions with their context" {
      val connector = TestConnector(mockk())
      val seen = CopyOnWriteArrayList<BackgroundException>()
      withScope { scope ->
        scope.launch { connector.backgroundExceptionsAsFlow().collect { seen += it } }
        delay(500)

        val boom = RuntimeException("boom")
        connector.record(boom)

        untilTrue(10.seconds) { seen.size == 1 } shouldBe true
        seen.first().context shouldBe "test-recipe"
        seen.first().throwable shouldBe boom
      }
    }

    "the flow unregisters its listener on cancel and receives nothing afterward" {
      val connector = TestConnector(mockk())
      val seen = CopyOnWriteArrayList<BackgroundException>()
      withScope { scope ->
        val job = scope.launch { connector.backgroundExceptionsAsFlow().collect { seen += it } }
        delay(500)
        job.cancelAndJoin()

        connector.record(RuntimeException("after cancel"))
        delay(500)
        seen.size shouldBe 0
      }
    }
  }
}
