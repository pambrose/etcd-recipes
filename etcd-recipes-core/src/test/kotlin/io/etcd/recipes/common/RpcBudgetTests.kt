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

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.GetResponse
import io.etcd.recipes.discovery.ServiceDiscovery
import io.etcd.recipes.discovery.ServiceInstance
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.election.LeaderSelectorListenerAdapter
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every RPC a recipe makes runs under its own `RpcResilience` (timeout, retries, and
 * metrics), not the library default, and reachability probes are cheap:
 *
 * - discovery queries, service caches and providers, a leader's lease revoke, and
 *   `getParticipants` reach the configured metrics;
 * - `putValuesWithKeepAlive` writes its keys in one transaction and revokes the lease when
 *   the block ends, so the keys go with it;
 * - `ping` makes one short attempt, and counts a definite server reply as reachable.
 */
class RpcBudgetTests : StringSpec() {
  private val base = "/common/${javaClass.simpleName}"

  private class RecordingMetrics : EtcdMetrics {
    val ops = CopyOnWriteArrayList<String>()

    override fun recordRpc(
      opName: String,
      duration: Duration,
      attempts: Int,
      failed: Boolean,
    ) {
      ops += opName
    }
  }

  private fun recorded(metrics: RecordingMetrics) = ResilienceConfig.DEFAULT.withMetrics(metrics)

  // Runs [block], clearing [metrics] first, and asserts it made at least one recorded RPC.
  private fun <T> recordsRpcs(
    what: String,
    metrics: RecordingMetrics,
    block: () -> T,
  ): T {
    metrics.ops.clear()
    return block().also { withClue("$what bypassed the recipe's RpcResilience") { metrics.ops.shouldNotBeEmpty() } }
  }

  // An endpoint nothing listens on: every attempt waits out its whole timeout.
  private fun unreachable() = connectToEtcd(listOf("http://127.0.0.1:1"))

  init {
    "discovery queries, service caches, and providers run under the recipe's RpcResilience" {
      connectToEtcd(urls) { client ->
        val path = "$base/discovery"
        client.deleteChildren(path)
        val metrics = RecordingMetrics()
        ServiceDiscovery(client, path, resilienceConfig = recorded(metrics)).use { sd ->
          sd.registerService(ServiceInstance("svc", "{}"))
          recordsRpcs("queryForNames", metrics) { sd.queryForNames() }
          recordsRpcs("queryForInstances", metrics) { sd.queryForInstances("svc") }
          recordsRpcs("serviceCache", metrics) { sd.serviceCache("svc").start() }
          sd.serviceProvider("svc").use { provider ->
            recordsRpcs("getAllInstances", metrics) { provider.getAllInstances() }
          }
        }
        client.deleteChildren(path)
      }
    }

    "a leader's lease revoke and getParticipants run under the given RpcResilience" {
      connectToEtcd(urls) { client ->
        val path = "$base/election"
        client.deleteChildren(path)
        val metrics = RecordingMetrics()
        val leaderLease = AtomicLong(0L)
        LeaderSelector(
          client,
          path,
          object : LeaderSelectorListenerAdapter() {
            override fun takeLeadership(selector: LeaderSelector) {
              leaderLease.store(client.getResponse("$path/LEADER").kvs.single().lease)
            }
          },
          resilience = recorded(metrics),
        ).use { selector ->
          selector.start()
          selector.waitOnLeadershipComplete(10.seconds) shouldBe true
          withClue("the leadership lease revoke bypassed the RpcResilience: ${metrics.ops}") {
            metrics.ops.contains("leaseRevoke(${leaderLease.load()})") shouldBe true
          }
        }
        recordsRpcs("getParticipants", metrics) {
          LeaderSelector.getParticipants(client, path, recorded(metrics).rpc)
        }
        client.deleteChildren(path)
      }
    }

    "putValuesWithKeepAlive writes its keys together and removes them when the block ends" {
      connectToEtcd(urls) { client ->
        val path = "$base/keep-alive"
        client.deleteChildren(path)
        val metrics = RecordingMetrics()
        val kvs = listOf("$path/a" to "1".asByteSequence, "$path/b" to "2".asByteSequence)
        client.putValuesWithKeepAlive(kvs, 30.seconds, rpc = recorded(metrics).rpc) {
          val revisions = kvs.map { (key, _) -> client.getResponse(key).kvs.single().createRevision }
          withClue("the keys were written in separate revisions") { revisions.distinct().size shouldBe 1 }
        }
        withClue("the keys outlived the block by up to the TTL") {
          client.getValue("$path/a").shouldBeNull()
          client.getValue("$path/b").shouldBeNull()
        }
        withClue("putValuesWithKeepAlive bypassed its rpc") { metrics.ops.shouldNotBeEmpty() }
      }
    }

    "getChildrenValues and deleteKeys take an RpcResilience" {
      connectToEtcd(urls) { client ->
        val path = "$base/helpers"
        client.putValue("$path/a", "1")
        val metrics = RecordingMetrics()
        recordsRpcs("getChildrenValues", metrics) { client.getChildrenValues(path, rpc = recorded(metrics).rpc) }
        recordsRpcs("deleteKeys", metrics) { client.deleteKeys("$path/a", rpc = recorded(metrics).rpc) }
      }
    }

    "ping makes one short attempt against an unreachable etcd" {
      unreachable().use { client ->
        var result: Boolean? = null
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
          result = client.ping()
          done.countDown()
        }
        withClue("ping kept retrying an unreachable etcd") { done.await(10, TimeUnit.SECONDS) shouldBe true }
        result shouldBe false
      }
    }

    "ping counts a definite server reply as reachable" {
      val kv = mockk<KV>()
      every { kv.get(any(), any()) } returns
        CompletableFuture.failedFuture<GetResponse>(StatusRuntimeException(Status.PERMISSION_DENIED))
      val client = mockk<Client> { every { kvClient } returns kv }
      withClue("an RBAC-scoped cluster reported unreachable") { client.ping() shouldBe true }
    }

    "a recipe's ping takes an RpcResilience" {
      unreachable().use { client ->
        TransientKeyValueProbe(client).use { recipe ->
          val done = CountDownLatch(1)
          thread(isDaemon = true) {
            recipe.ping(RpcResilience.PROBE)
            done.countDown()
          }
          done.await(10, TimeUnit.SECONDS) shouldBe true
        }
      }
    }
  }

  // The smallest EtcdConnector, to reach its ping.
  private class TransientKeyValueProbe(
    client: Client,
  ) : EtcdConnector(client)
}
