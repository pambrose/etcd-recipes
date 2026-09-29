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

package io.etcd.recipes.keyvalue

import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `TransientKeyValue` holds no thread of its own while it publishes, so instances can share
 * a small executor, and a failed `start()` can be retried: the retry publishes afresh and
 * `close()` still removes the key. `close()` on an instance that never started is a no-op.
 */
class TransientKeyValueLifecycleTests : StringSpec() {
  private val base = "/keyvalue/${javaClass.simpleName}"

  init {
    "instances sharing a single-thread executor all start" {
      connectToEtcd(urls) { client ->
        val path = "$base/shared-executor"
        client.deleteChildren(path)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val started = CountDownLatch(1)
        val instances = mutableListOf<TransientKeyValue>()
        thread(isDaemon = true) {
          repeat(2) { i -> instances += TransientKeyValue(client, "$path/k$i", "v$i", userExecutor = pool) }
          started.countDown()
        }
        withClue("the second instance's start() waited on a thread the first one parked") {
          started.await(10, TimeUnit.SECONDS) shouldBe true
        }
        client.getValue("$path/k1")?.asString shouldBe "v1"
        instances.forEach { it.close() }
        pool.shutdownNow()
        client.deleteChildren(path)
      }
    }

    "a start() retried after a failure publishes, and close() removes the key" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/retry"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val kv = TransientKeyValue(client, "$path/key", "value", autoStart = false)
        client.beforeLeaseGrant.store {
          throw StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("denied"))
        }
        shouldThrow<EtcdRecipeRuntimeException> { kv.start() }
        withClue("the retry rethrew the first attempt's failure") { shouldNotThrowAny { kv.start() } }
        etcd.getValue("$path/key")?.asString shouldBe "value"
        kv.close()
        withClue("close() left the key published") { etcd.getValue("$path/key").shouldBeNull() }
        etcd.deleteChildren(path)
      }
    }

    "close() on an instance that never started does not throw" {
      connectToEtcd(urls) { client ->
        shouldNotThrowAny { TransientKeyValue(client, "$base/never-started", "v", autoStart = false).close() }
      }
    }
  }
}
