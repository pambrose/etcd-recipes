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

package io.etcd.recipes.cache

import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class PathChildrenCacheTrailingSlashTests : StringSpec() {
    private val base = "/caches/${javaClass.simpleName}"

    init {
        // Regression test: a cachePath that ends in '/' must strip child names the
        // same way the live watcher does (by the trailing-prefix length), not by
        // cachePath.length + 1, which over-strips the first char of every child
        // ("bar" -> "ar") and corrupts the snapshot/rebuild views of the cache.
        "a trailing-slash cachePath strips child names consistently" {
            connectToEtcd(urls) { client ->
                client.deleteChildren(base)
                client.putValue("$base/bar", "v1")

                // Note the trailing slash on the cachePath.
                PathChildrenCache(client, "$base/").use { cache ->
                    cache.start(buildInitial = true)

                    // Snapshot path (loadDataAndStartWatcher): full name, not "ar".
                    cache.currentDataAsMap.keys shouldContainExactlyInAnyOrder listOf("bar")
                    cache.getCurrentData("bar")?.asString shouldBe "v1"

                    // Live watcher PUT lands in the same key space — no duplicate/stale key.
                    client.putValue("$base/baz", "v2")
                    pollUntil(5.seconds) { cache.getCurrentData("baz") != null } shouldBe true
                    cache.getCurrentData("baz")?.asString shouldBe "v2"
                    cache.currentDataAsMap.keys shouldContainExactlyInAnyOrder listOf("bar", "baz")

                    // rebuild() re-reads via the same prefix and must agree.
                    cache.rebuild()
                    cache.currentDataAsMap.keys shouldContainExactlyInAnyOrder listOf("bar", "baz")
                    cache.getCurrentData("bar")?.asString shouldBe "v1"
                }

                client.deleteChildren(base)
            }
        }
    }
}
