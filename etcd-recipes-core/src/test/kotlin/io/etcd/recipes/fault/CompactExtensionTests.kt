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

package io.etcd.recipes.fault

import io.etcd.recipes.common.compact
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.urls
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Exercises [compact] against a private Testcontainers etcd. Compaction is
 * cluster-wide, so this must never run against the shared local etcd.
 */
class CompactExtensionTests : StringSpec() {
  private val path = "/fault/${javaClass.simpleName}"

  init {
    "compact removes read access to history below the given revision" {
      assumeFaultInjection()
      connectToEtcd(urls) { client ->
        client.deleteChildren(path)
        client.putValue("$path/key", "v1")
        val rev1 = client.getResponse("$path/key").kvs.first().modRevision
        client.putValue("$path/key", "v2")
        val rev2 = client.getResponse("$path/key").kvs.first().modRevision

        client.compact(rev2)

        val thrown =
          shouldThrowAny {
            client.getResponse("$path/key", getOption { withRevision(rev1) })
          }
        generateSequence(thrown as Throwable) { it.cause.takeIf { c -> c !== it } }
          .any { it.message?.contains("compacted") == true } shouldBe true

        client.getValue("$path/key", "def") shouldBe "v2"
      }
    }
  }
}
