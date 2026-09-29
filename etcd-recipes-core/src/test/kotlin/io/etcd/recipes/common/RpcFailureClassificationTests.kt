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
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Lock
import io.etcd.jetcd.common.exception.ErrorCode
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lock.UnlockResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The RPC engine against failures shaped the way jetcd actually reports them. jetcd's KV,
 * lease, and lock calls fail with a raw gRPC [StatusRuntimeException] (wrapped in an
 * `ExecutionException` by `future.get`), never an `EtcdException`, which is all the
 * engine's retry check used to recognize — so status-based retries never fired.
 *
 * Retry policy: reads retry retriable statuses and attempt timeouts; so do `unlock` and
 * `leaseGrant`, whose duplicates are harmless. Plain writes (put, delete, compact) make one
 * bounded attempt: a write that failed or timed out may still have been applied, and a
 * blind retry could re-apply it after a newer write. Every failure reaches the caller as an
 * [EtcdRecipeRuntimeException] carrying the original cause, and an interrupt anywhere in
 * the engine surfaces the same way with the thread's interrupt flag restored.
 */
class RpcFailureClassificationTests : StringSpec() {
  private fun grpc(
    status: Status,
    description: String,
  ) = StatusRuntimeException(status.withDescription(description))

  private fun unavailable() = grpc(Status.UNAVAILABLE, "etcdserver: leader changed")

  private fun quick() = RpcResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds), 5.seconds)

  private fun Throwable.causes() = generateSequence(this) { it.cause.takeIf { c -> c !== it } }

  init {
    "a read retries a gRPC UNAVAILABLE, the way jetcd reports it" {
      val calls = AtomicInt(0)
      val result =
        retryRpc(quick(), "read") {
          if (calls.incrementAndFetch() <=
            2
          )
            CompletableFuture.failedFuture(unavailable())
            else
            CompletableFuture.completedFuture("ok")
        }
      result shouldBe "ok"
      calls.load() shouldBe 3
    }

    "a non-retriable gRPC status surfaces as EtcdRecipeRuntimeException carrying the status" {
      val calls = AtomicInt(0)
      val e =
        shouldThrow<EtcdRecipeRuntimeException> {
          retryRpc<String>(quick(), "read(/x)") {
            calls.incrementAndFetch()
            CompletableFuture.failedFuture(grpc(Status.PERMISSION_DENIED, "etcdserver: permission denied"))
          }
        }
      calls.load() shouldBe 1
      e.message!! shouldContain "read(/x)"
      e.causes().any { it is StatusRuntimeException && it.status.code == Status.Code.PERMISSION_DENIED } shouldBe true
    }

    "a failed single-attempt call surfaces as EtcdRecipeRuntimeException carrying the status" {
      val e =
        shouldThrow<EtcdRecipeRuntimeException> {
          awaitRpc(quick(), "transaction", CompletableFuture.failedFuture<String>(unavailable()))
        }
      e.message!! shouldContain "transaction"
      e.causes().any { it is StatusRuntimeException } shouldBe true
    }

    "an interrupt during the retry backoff surfaces as EtcdRecipeRuntimeException with the flag restored" {
      val rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 30.seconds), 5.seconds)
      val failedOnce = CountDownLatch(1)
      var thrown: Throwable? = null
      var interruptedAfter = false
      val t =
        thread {
          try {
            retryRpc<String>(rpc, "read") {
              failedOnce.countDown()
              CompletableFuture.failedFuture(
                EtcdExceptionFactory.newEtcdException(ErrorCode.UNAVAILABLE, "unavailable"),
              )
            }
          } catch (e: Throwable) {
            thrown = e
            interruptedAfter = Thread.currentThread().isInterrupted
          }
        }
      failedOnce.awaitOrFail(5.seconds, "the first attempt")
      Thread.sleep(200) // into the 30s backoff
      t.interrupt()
      t.join(TimeUnit.SECONDS.toMillis(10))
      withClue("thrown: $thrown") { (thrown is EtcdRecipeRuntimeException) shouldBe true }
      interruptedAfter shouldBe true
    }

    "an interrupt while awaiting a single-attempt call surfaces the same way" {
      val waiting = CountDownLatch(1)
      var thrown: Throwable? = null
      var interruptedAfter = false
      val t =
        thread {
          try {
            waiting.countDown()
            awaitRpc(quick(), "transaction", CompletableFuture<String>()) // never completes
          } catch (e: Throwable) {
            thrown = e
            interruptedAfter = Thread.currentThread().isInterrupted
          }
        }
      waiting.awaitOrFail(5.seconds, "the waiter")
      Thread.sleep(200)
      t.interrupt()
      t.join(TimeUnit.SECONDS.toMillis(10))
      withClue("thrown: $thrown") { (thrown is EtcdRecipeRuntimeException) shouldBe true }
      interruptedAfter shouldBe true
    }

    "leaseRevoke, which swallows failures, keeps an interrupted caller's interrupt flag" {
      val client =
        mockk<Client> {
          every { leaseClient } returns mockk<Lease> { every { revoke(any()) } returns CompletableFuture() }
        }
      var interruptedAfter = false
      val t =
        thread {
          Thread.currentThread().interrupt() // e.g. an executor shutdownNow() during cleanup
          client.leaseRevoke(mockk<LeaseGrantResponse> { every { id } returns 42L }, quick())
          interruptedAfter = Thread.currentThread().isInterrupted
        }
      t.join(TimeUnit.SECONDS.toMillis(10))
      interruptedAfter shouldBe true
    }

    "each plain write is a single attempt that surfaces a gRPC UNAVAILABLE" {
      val calls = mutableMapOf<String, AtomicInt>()

      fun <T> counted(op: String): CompletableFuture<T> {
        calls.getOrPut(op) { AtomicInt(0) }.incrementAndFetch()
        return CompletableFuture.failedFuture(unavailable())
      }
      val client =
        mockk<Client> {
          every { kvClient } returns
            mockk<KV> {
              every { put(any(), any<ByteSequence>(), any()) } answers { counted("put") }
              every { delete(any<ByteSequence>()) } answers { counted("delete") }
              every { delete(any(), any()) } answers { counted("deleteChildren") }
              every { compact(any(), any()) } answers { counted("compact") }
            }
        }
      val writes =
        mapOf<String, () -> Unit>(
          "put" to { client.putValue("/w/k", "v", rpc = quick()) },
          "delete" to { client.deleteKey("/w/k", quick()) },
          "deleteChildren" to { client.deleteChildren("/w", quick()) },
          "compact" to { client.compact(10L, rpc = quick()) },
        )
      for ((op, write) in writes) {
        withClue(op) {
          shouldThrow<EtcdRecipeRuntimeException> { write() }
          calls[op]!!.load() shouldBe 1
        }
      }
    }

    "a write is not retried after an attempt timeout" {
      val puts = AtomicInt(0)
      val client =
        mockk<Client> {
          every { kvClient } returns
            mockk<KV> {
              every { put(any(), any<ByteSequence>(), any()) } answers {
                puts.incrementAndFetch()
                CompletableFuture() // never completes: the attempt may still land later
              }
            }
        }
      val rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds), 200.milliseconds)
      shouldThrow<EtcdRecipeRuntimeException> { client.putValue("/w/k", "v", rpc = rpc) }
      puts.load() shouldBe 1
    }

    "unlock and leaseGrant, whose duplicates are harmless, still retry a gRPC UNAVAILABLE" {
      val unlocks = AtomicInt(0)
      val grants = AtomicInt(0)
      val client =
        mockk<Client> {
          every { lockClient } returns
            mockk<Lock> {
              every { unlock(any()) } answers {
                if (unlocks.incrementAndFetch() == 1) {
                  CompletableFuture.failedFuture(unavailable())
                } else {
                  CompletableFuture.completedFuture(mockk<UnlockResponse>())
                }
              }
            }
          every { leaseClient } returns
            mockk<Lease> {
              every { grant(any()) } answers {
                if (grants.incrementAndFetch() == 1) {
                  CompletableFuture.failedFuture(unavailable())
                } else {
                  CompletableFuture.completedFuture(mockk<LeaseGrantResponse>())
                }
              }
            }
        }
      client.unlock("/locks/x", quick())
      client.leaseGrant(2.seconds, quick())
      unlocks.load() shouldBe 2
      grants.load() shouldBe 2
    }
  }
}
