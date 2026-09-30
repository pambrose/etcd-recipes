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

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Watch
import io.etcd.jetcd.common.exception.EtcdExceptionFactory
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.WatchOption
import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.common.EtcdMetrics
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.queue.DistributedWorkQueue
import io.etcd.recipes.queue.TypedDistributedQueue
import io.etcd.recipes.queue.WorkQueueConfig
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Coroutine flows and parity:
 *
 * - a watch that is abandoned for good completes its flows (`watchAsFlow`,
 *   `leadershipAsFlow`) or fails them (`watchEventsAsFlow`), instead of leaving the
 *   collector suspended forever;
 * - a failed leadership re-read after a recovery ends the flow with `WatchFailed`;
 * - the suspend RPC engine records `EtcdMetrics`, and keeps a timeout's cause;
 * - the blocking calls added after the coroutine layer have suspending twins.
 */
class FlowCompletionAndParityTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  // A client whose watches are captured, so a test can kill them, and whose GETs return an
  // empty key at revision 10 for the first [okGets] calls and PERMISSION_DENIED after that.
  private class Mocks(
    okGets: Int = Int.MAX_VALUE,
  ) {
    val listeners = CopyOnWriteArrayList<Watch.Listener>()
    private val gets = AtomicInt(0)

    val client: Client =
      mockk {
        every { kvClient } returns
          mockk<KV> {
            every { get(any<ByteSequence>(), any()) } answers {
              if (gets.incrementAndFetch() > okGets) {
                CompletableFuture.failedFuture(StatusRuntimeException(Status.PERMISSION_DENIED))
              } else {
                CompletableFuture.completedFuture(
                  mockk<GetResponse> {
                    every { kvs } returns emptyList()
                    every { isMore } returns false
                    every { header } returns mockk { every { revision } returns 10L }
                  },
                )
              }
            }
          }
        every { watchClient } returns
          mockk<Watch> {
            every { watch(any<ByteSequence>(), any<WatchOption>(), any<Watch.Listener>()) } answers {
              listeners += thirdArg<Watch.Listener>()
              mockk<Watch.Watcher>(relaxed = true)
            }
          }
      }

    suspend fun awaitListener(index: Int = 0): Watch.Listener {
      untilTrue(10.seconds) { listeners.size > index } shouldBe true
      return listeners[index]
    }
  }

  private fun Watch.Listener.die(cause: Throwable = RuntimeException("fatal watch error")) {
    onError(cause)
    onCompleted()
  }

  private class RecordingMetrics : EtcdMetrics {
    val rpcs = CopyOnWriteArrayList<String>()

    override fun recordRpc(
      opName: String,
      duration: Duration,
      attempts: Int,
      failed: Boolean,
    ) {
      rpcs += opName
    }
  }

  init {
    "watchAsFlow completes once its watch is abandoned" {
      val mocks = Mocks()
      runBlocking {
        val events = async { mocks.client.watchAsFlow("/k", resilience = WatchResilience.DISABLED).toList() }
        mocks.awaitListener().die()
        val collected = withClue("the flow never completed") { withTimeout(10.seconds) { events.await() } }
        (collected.last() as WatchFlowEvent.Recovery).event::class shouldBe WatchRecoveryEvent.Failed::class
      }
    }

    "watchEventsAsFlow fails once its watch is abandoned" {
      val mocks = Mocks()
      runBlocking {
        val events =
          async { runCatching { mocks.client.watchEventsAsFlow("/k", resilience = WatchResilience.DISABLED).toList() } }
        mocks.awaitListener().die()
        val outcome = withClue("the flow never ended") { withTimeout(10.seconds) { events.await() } }
        (outcome.exceptionOrNull() is EtcdRecipeRuntimeException) shouldBe true
      }
    }

    "leadershipAsFlow completes after reporting that its watch was abandoned" {
      val mocks = Mocks()
      runBlocking {
        val events =
          async { mocks.client.leadershipAsFlow("/election", resilience = WatchResilience.DISABLED).toList() }
        mocks.awaitListener().die()
        val collected = withClue("the flow never completed") { withTimeout(10.seconds) { events.await() } }
        (collected.last() is LeadershipEvent.WatchFailed) shouldBe true
      }
    }

    "leadershipAsFlow ends with WatchFailed when its re-read after a resync fails" {
      val mocks = Mocks(okGets = 1) // the seed read works; the re-read after the resync doesn't
      val quick = WatchResilience(RetryPolicy.bounded(maxAttempts = 5, delay = 10.milliseconds))
      runBlocking {
        val events = async { mocks.client.leadershipAsFlow("/election", resilience = quick).toList() }
        mocks.awaitListener().die(EtcdExceptionFactory.newCompactedException(5))
        val collected = withClue("the failed re-read was swallowed") { withTimeout(10.seconds) { events.await() } }
        (collected.last() is LeadershipEvent.WatchFailed) shouldBe true
      }
    }

    "leadershipAsFlow and the suspend RPC engine run under a given RpcResilience, with metrics" {
      connectToEtcd(urls) { client ->
        val path = "$base/metrics"
        client.deleteChildren(path)
        val metrics = RecordingMetrics()
        val rpc = RpcResilience.DEFAULT.withMetrics(metrics)
        runBlocking {
          client.awaitPutValue("$path/k", "v", rpc = rpc)
          client.awaitGetValue("$path/k", rpc = rpc)
          client.leadershipAsFlow("$path/election", rpc = rpc).let { flow -> withTimeout(10.seconds) { flow.first() } }
        }
        withClue("recorded: ${metrics.rpcs}") {
          metrics.rpcs.any { it.startsWith("putValue") } shouldBe true
          metrics.rpcs.any { it.startsWith("getResponse") || it.startsWith("getValue") } shouldBe true
          metrics.rpcs.size shouldBe 3
        }
        client.deleteChildren(path)
      }
    }

    "a suspended single-attempt call that times out keeps the timeout as its cause" {
      runBlocking {
        val failure =
          shouldThrow<EtcdRecipeRuntimeException> {
            suspendAwaitRpc(RpcResilience(RetryPolicy.never, 100.milliseconds), "txn", CompletableFuture<String>())
          }
        (failure.cause is TimeoutException) shouldBe true
      }
    }

    "the recipes added after the coroutine layer have suspending twins" {
      connectToEtcd(urls) { client ->
        val path = "$base/twins"
        client.deleteChildren(path)
        runBlocking {
          LeaderLatch(client, "$path/latch").use { latch ->
            latch.awaitStart()
            latch.awaitLeadership(10.seconds) shouldBe true
          }
          client.putValue("$path/node", "n")
          NodeCache(client, "$path/node", StringCodec).use { cache ->
            cache.awaitStart()
            cache.current shouldBe "n"
          }
          ServiceDiscovery(client, "$path/discovery").use { sd ->
            sd.registerService(ServiceInstance("svc", "{}"))
            sd.serviceProvider("svc").use { provider ->
              provider.awaitStart()
              provider.awaitGetAllInstances().size shouldBe 1
              provider.awaitGetInstance().name shouldBe "svc"
            }
          }
          TypedDistributedQueue(client, "$path/typed", StringCodec).use { queue ->
            queue.awaitEnqueue("t")
            queue.receive() shouldBe "t"
          }
          DistributedWorkQueue(client, "$path/work", WorkQueueConfig(maxDeliveries = 1)).use { queue ->
            queue.enqueue("poison")
            queue.awaitReceive().requeue() shouldBe true
            queue.awaitTryReceive() shouldBe null // dead-lettered on the next receive
            queue.awaitDeadLetters().map { it.id }.size shouldBe 1
          }
        }
        client.deleteChildren(path)
      }
    }
  }
}
