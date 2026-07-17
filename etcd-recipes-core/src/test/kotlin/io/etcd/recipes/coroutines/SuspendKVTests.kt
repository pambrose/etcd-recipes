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

import io.etcd.jetcd.options.GetOption
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Integration round-trips for the suspending twins of the common/ extension surface,
 * called directly from the suspend test context — no threads, no latches.
 */
class SuspendKVTests : StringSpec() {
  private val base = "/coroutines/${javaClass.simpleName}"

  init {
    "typed put and get round-trip through the suspend twins" {
      connectToEtcd(urls).use { client ->
        client.awaitDeleteChildren(base)
        val path = "$base/typed"

        client.awaitPutValue("$path/str", "hello")
        client.awaitGetValue("$path/str", "none") shouldBe "hello"

        client.awaitPutValue("$path/int", 42)
        client.awaitGetValue("$path/int", -1) shouldBe 42

        client.awaitPutValue("$path/long", 42_000_000_000L)
        client.awaitGetValue("$path/long", -1L) shouldBe 42_000_000_000L

        client.awaitGetValue("$path/missing", "fallback") shouldBe "fallback"
        client.awaitGetValue("$path/missing") shouldBe null
      }
    }

    "children twins list, count, and delete" {
      connectToEtcd(urls).use { client ->
        client.awaitDeleteChildren(base)
        val path = "$base/children"

        client.awaitPutValue("$path/a", "1")
        client.awaitPutValue("$path/b", "2")
        client.awaitPutValue("$path/c", "3")

        client.awaitGetChildCount(path) shouldBe 3L
        client.awaitGetChildrenKeys(path).map { it.substringAfterLast('/') } shouldContainExactly
          listOf("a", "b", "c")
        client.awaitGetChildrenValues(path).map { it.asString } shouldContainExactly listOf("1", "2", "3")
        client.awaitGetChildren(path).map { it.second.asString } shouldContainExactly listOf("1", "2", "3")

        client.awaitGetFirstChild(path, GetOption.SortTarget.KEY)
          .kvs.single().key.asString.substringAfterLast('/') shouldBe "a"
        client.awaitGetLastChild(path, GetOption.SortTarget.KEY)
          .kvs.single().key.asString.substringAfterLast('/') shouldBe "c"

        client.awaitDeleteChildren(path).size shouldBe 3
        client.awaitGetChildCount(path) shouldBe 0L
      }
    }

    "getResponse, key presence, and single-key delete" {
      connectToEtcd(urls).use { client ->
        client.awaitDeleteChildren(base)
        val key = "$base/presence/key"

        client.awaitIsKeyNotPresent(key) shouldBe true
        client.awaitPutValue(key, "here")
        client.awaitIsKeyPresent(key) shouldBe true
        client.awaitGetResponse(key).kvs.single().value.asString shouldBe "here"
        client.awaitGetKeyValuePairs(key, getOption { isPrefix(true) }).single().second.asString shouldBe "here"

        client.awaitDeleteKey(key)
        client.awaitIsKeyNotPresent(key) shouldBe true
      }
    }

    "awaitTransaction executes a CAS create exactly once" {
      connectToEtcd(urls).use { client ->
        client.awaitDeleteChildren(base)
        val key = "$base/txn/key"

        val first =
          client.awaitTransaction {
            If(key.doesNotExist)
            Then(key setTo "claimed")
          }
        first.isSucceeded shouldBe true

        val second =
          client.awaitTransaction {
            If(key.doesNotExist)
            Then(key setTo "stolen")
          }
        second.isSucceeded shouldBe false
        client.awaitGetValue(key, "none") shouldBe "claimed"
      }
    }

    "lease grant, raw lock, unlock, and revoke sequence" {
      connectToEtcd(urls).use { client ->
        client.awaitDeleteChildren(base)
        val lockName = "$base/rawlock"

        val lease = client.awaitLeaseGrant(10.seconds)
        val lock = client.awaitLock(lockName, lease.id)
        lock.key.asString.startsWith(lockName) shouldBe true

        client.awaitUnlock(lock.key.asString)
        client.awaitLeaseRevoke(lease)
      }
    }
  }
}
