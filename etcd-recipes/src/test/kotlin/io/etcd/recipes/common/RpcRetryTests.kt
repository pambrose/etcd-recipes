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

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Txn
import io.etcd.jetcd.common.exception.ErrorCode
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.jetcd.kv.TxnResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Unit tests for the blocking-RPC retry/timeout engine. Every extension-layer call
 * used to block on an unbounded `future.get()`; [retryRpc] bounds each attempt with
 * the configured timeout and retries retriable statuses under the policy.
 * No etcd needed.
 */
class RpcRetryTests : StringSpec() {
  private fun unavailable() = EtcdExceptionFactory.newEtcdException(ErrorCode.UNAVAILABLE, "etcd unavailable")

  private fun quick() = RpcResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds), 5.seconds)

  init {
    "retries retriable failures until success" {
      val calls = AtomicInteger(0)
      val result =
        retryRpc(quick(), "test-op") {
          if (calls.incrementAndGet() <= 2) {
            CompletableFuture.failedFuture(unavailable())
          } else {
            CompletableFuture.completedFuture("ok")
          }
        }
      result shouldBe "ok"
      calls.get() shouldBe 3
    }

    "gives up after policy exhaustion and wraps the cause with attempt context" {
      val calls = AtomicInteger(0)
      val rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 2, delay = 10.milliseconds), 5.seconds)
      val thrown =
        shouldThrow<EtcdRecipeRuntimeException> {
          retryRpc<String>(rpc, "doomed-op") {
            calls.incrementAndGet()
            CompletableFuture.failedFuture(unavailable())
          }
        }
      calls.get() shouldBe 3 // initial + 2 retries
      thrown.message!! shouldContain "doomed-op"
      thrown.message!! shouldContain "3"
      generateSequence(thrown as Throwable) { it.cause.takeIf { c -> c !== it } }
        .any { it.message?.contains("unavailable") == true } shouldBe true
    }

    "a hung call fails at operationTimeout instead of parking forever" {
      val rpc = RpcResilience(RetryPolicy.never, operationTimeout = 200.milliseconds)
      val start = TimeSource.Monotonic.markNow()
      shouldThrowAny {
        retryRpc<String>(rpc, "hung-op") { CompletableFuture() } // never completes
      }
      (start.elapsedNow() < 5.seconds) shouldBe true
    }

    "non-retriable failures propagate without retry" {
      val calls = AtomicInteger(0)
      shouldThrowAny {
        retryRpc<String>(quick(), "bad-request") {
          calls.incrementAndGet()
          CompletableFuture.failedFuture(IllegalArgumentException("bad key"))
        }
      }
      calls.get() shouldBe 1
    }

    "DISABLED reproduces one-shot semantics" {
      val calls = AtomicInteger(0)
      shouldThrowAny {
        retryRpc<String>(RpcResilience.DISABLED, "one-shot") {
          calls.incrementAndGet()
          CompletableFuture.failedFuture(unavailable())
        }
      }
      calls.get() shouldBe 1
    }

    "transaction is never retried even on retriable failures" {
      val commits = AtomicInteger(0)
      val txn =
        mockk<Txn> {
          every { If(*anyVararg()) } returns this
          every { Then(*anyVararg()) } returns this
          every { commit() } answers {
            commits.incrementAndGet()
            CompletableFuture.failedFuture<TxnResponse>(unavailable())
          }
        }
      val client =
        mockk<Client> {
          every { kvClient } returns mockk<KV> { every { txn() } returns txn }
        }

      shouldThrowAny {
        client.transaction { If("x".doesExist) }
      }
      commits.get() shouldBe 1
    }

    "reads route through the retry engine" {
      val calls = AtomicInteger(0)
      val client =
        mockk<Client> {
          every { kvClient } returns
            mockk<KV> {
              every { get(any<ByteSequence>(), any()) } answers {
                if (calls.incrementAndGet() <= 1) {
                  CompletableFuture.failedFuture(unavailable())
                } else {
                  CompletableFuture.completedFuture(
                    mockk {
                      every { kvs } returns emptyList()
                      every { isMore } returns false
                    },
                  )
                }
              }
            }
        }

      client.getResponse("/rpc/read").kvs.isEmpty() shouldBe true
      calls.get() shouldBe 2
    }
  }
}
