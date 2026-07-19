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

import io.etcd.jetcd.common.exception.ErrorCode
import io.etcd.jetcd.common.exception.EtcdException
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the suspending RPC retry/timeout engine — the coroutine twin of
 * RpcRetryTests. Virtual time via runTest: backoff delays and operation timeouts
 * cost no wall-clock. No etcd needed.
 */
class SuspendRpcRetryTests : StringSpec() {
  private fun unavailable() = EtcdExceptionFactory.newEtcdException(ErrorCode.UNAVAILABLE, "etcd unavailable")

  private fun quick() = RpcResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds), 5.seconds)

  init {
    "retries retriable failures until success" {
      runTest {
        val calls = AtomicInt(0)
        val result =
          suspendRetryRpc(quick(), "test-op") {
            if (calls.incrementAndFetch() <= 2) {
              CompletableFuture.failedFuture(unavailable())
            } else {
              CompletableFuture.completedFuture("ok")
            }
          }
        result shouldBe "ok"
        calls.load() shouldBe 3
      }
    }

    "retriable causes are found through the exception chain" {
      runTest {
        val calls = AtomicInt(0)
        val result =
          suspendRetryRpc(quick(), "wrapped-op") {
            if (calls.incrementAndFetch() <= 1) {
              CompletableFuture.failedFuture(RuntimeException("wrapper", unavailable()))
            } else {
              CompletableFuture.completedFuture("ok")
            }
          }
        result shouldBe "ok"
        calls.load() shouldBe 2
      }
    }

    "gives up after policy exhaustion and wraps the cause with attempt context" {
      runTest {
        val calls = AtomicInt(0)
        val rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 2, delay = 10.milliseconds), 5.seconds)
        val thrown =
          shouldThrow<EtcdRecipeRuntimeException> {
            suspendRetryRpc<String>(rpc, "doomed-op") {
              calls.incrementAndFetch()
              CompletableFuture.failedFuture(unavailable())
            }
          }
        calls.load() shouldBe 3 // initial + 2 retries
        thrown.message!! shouldContain "doomed-op"
        thrown.message!! shouldContain "3"
        generateSequence(thrown as Throwable) { it.cause.takeIf { c -> c !== it } }
          .any { it.message?.contains("unavailable") == true } shouldBe true
      }
    }

    "a hung call fails at operationTimeout and cancels the in-flight future" {
      runTest {
        val futures: MutableList<CompletableFuture<String>> = []
        val rpc = RpcResilience(RetryPolicy.never, operationTimeout = 200.milliseconds)
        shouldThrow<EtcdRecipeRuntimeException> {
          suspendRetryRpc(rpc, "hung-op") {
            CompletableFuture<String>().also { futures += it } // never completes
          }
        }
        futures.size shouldBe 1
        futures.single().isCancelled shouldBe true
      }
    }

    "an attempt timeout is retriable under the policy" {
      runTest {
        val calls = AtomicInt(0)
        val rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 3, delay = 10.milliseconds), 100.milliseconds)
        val result =
          suspendRetryRpc(rpc, "slow-then-ok") {
            if (calls.incrementAndFetch() <= 1) {
              CompletableFuture() // hangs; times out at 100ms virtual
            } else {
              CompletableFuture.completedFuture("ok")
            }
          }
        result shouldBe "ok"
        calls.load() shouldBe 2
      }
    }

    "non-retriable failures propagate without retry" {
      runTest {
        val calls = AtomicInt(0)
        shouldThrow<IllegalArgumentException> {
          suspendRetryRpc<String>(quick(), "bad-request") {
            calls.incrementAndFetch()
            CompletableFuture.failedFuture(IllegalArgumentException("bad key"))
          }
        }
        calls.load() shouldBe 1
      }
    }

    "external cancellation during the await propagates promptly without retry" {
      runTest {
        val calls = AtomicInt(0)
        val futures: MutableList<CompletableFuture<String>> = []
        val rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds), Duration.INFINITE)
        val job =
          launch {
            suspendRetryRpc<String>(rpc, "cancelled-op") {
              calls.incrementAndFetch()
              CompletableFuture<String>().also { futures += it } // hangs; no timeout configured
            }
          }
        testScheduler.runCurrent() // let the launch reach the await
        job.cancel()
        job.join()
        job.isCancelled shouldBe true
        calls.load() shouldBe 1
        futures.single().isCancelled shouldBe true
      }
    }

    "external cancellation during backoff is prompt: no further attempt" {
      runTest {
        val calls = AtomicInt(0)
        val rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 60.seconds), 5.seconds)
        val job =
          launch {
            suspendRetryRpc<String>(rpc, "backoff-op") {
              calls.incrementAndFetch()
              CompletableFuture.failedFuture(unavailable())
            }
          }
        testScheduler.runCurrent() // first attempt fails; coroutine parks in the 60s backoff delay
        calls.load() shouldBe 1
        job.cancel()
        job.join()
        job.isCancelled shouldBe true
        calls.load() shouldBe 1
      }
    }

    "suspendAwaitRpc returns a completed value" {
      runTest {
        suspendAwaitRpc(quick(), "done-op", CompletableFuture.completedFuture("ok")) shouldBe "ok"
      }
    }

    "suspendAwaitRpc never retries, even on retriable failures" {
      runTest {
        shouldThrow<EtcdException> {
          suspendAwaitRpc<String>(quick(), "one-shot", CompletableFuture.failedFuture(unavailable()))
        }
      }
    }

    "suspendAwaitRpc wraps a timeout and cancels the future" {
      runTest {
        val future = CompletableFuture<String>()
        val rpc = RpcResilience(RetryPolicy.never, operationTimeout = 200.milliseconds)
        val thrown =
          shouldThrow<EtcdRecipeRuntimeException> {
            suspendAwaitRpc(rpc, "hung-txn", future)
          }
        thrown.message!! shouldContain "timed out"
        future.isCancelled shouldBe true
      }
    }

    "suspendAwaitRpc external cancellation cancels the future and propagates" {
      runTest {
        val future = CompletableFuture<String>()
        val rpc = RpcResilience(RetryPolicy.never, operationTimeout = Duration.INFINITE)
        val job = launch { suspendAwaitRpc(rpc, "cancelled-txn", future) }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        job.isCancelled shouldBe true
        future.isCancelled shouldBe true
      }
    }

    "cancellation exceptions are never swallowed as retriable" {
      runTest {
        val calls = AtomicInt(0)
        shouldThrow<CancellationException> {
          suspendRetryRpc<String>(quick(), "cancel-inside") {
            calls.incrementAndFetch()
            CompletableFuture.failedFuture(CancellationException("externally cancelled future"))
          }
        }
        calls.load() shouldBe 1
      }
    }
  }
}
