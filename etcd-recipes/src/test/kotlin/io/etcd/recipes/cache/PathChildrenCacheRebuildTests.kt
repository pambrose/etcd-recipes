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

import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class PathChildrenCacheRebuildTests : StringSpec() {
    private val base = "/caches/${javaClass.simpleName}"
    private val childCount = 20

    init {
        // Regression test for #8: rebuild() must reconcile the live map in place, so a
        // reader never observes the empty/partial window that the old clear()-then-refill
        // produced. A concurrent reader sampling currentDataAsMap across many rebuilds
        // would catch size 0 with overwhelming probability pre-fix; post-fix the map is
        // never emptied while the children still exist.
        "rebuild() never exposes an empty window while children exist".config(timeout = 60.seconds) {
            connectToEtcd(urls) { client ->
                client.deleteChildren(base)
                repeat(childCount) { client.putValue("$base/k$it", "v$it") }

                PathChildrenCache(client, base).use { cache ->
                    cache.start(buildInitial = true)
                    cache.currentDataAsMap.size shouldBe childCount

                    val sawEmpty = AtomicBoolean(false)
                    val stop = AtomicBoolean(false)
                    val reader =
                        thread {
                            while (!stop.get()) {
                                if (cache.currentDataAsMap.isEmpty()) sawEmpty.set(true)
                            }
                        }

                    repeat(100) { cache.rebuild() }

                    stop.set(true)
                    reader.join(5_000)

                    sawEmpty.get() shouldBe false
                    cache.currentDataAsMap.size shouldBe childCount
                }

                client.deleteChildren(base)
            }
        }
    }
}
