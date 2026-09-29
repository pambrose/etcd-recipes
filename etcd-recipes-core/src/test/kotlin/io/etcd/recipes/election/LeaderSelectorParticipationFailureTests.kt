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

package io.etcd.recipes.election

import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors

/**
 * Registering an election participant must report an infrastructure failure as one. The
 * participation setup caught every `EtcdRecipeRuntimeException` and rethrew it as a
 * cause-less "Participation registration failed" (the lost-CAS message), so a refused or
 * failed lease grant was indistinguishable from another candidate already holding the key.
 */
class LeaderSelectorParticipationFailureTests : StringSpec() {
  private val base = "/election/${javaClass.simpleName}"

  init {
    "participation reports a failed lease grant instead of a lost CAS" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/grant-failure"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)
        val executor = Executors.newCachedThreadPool()
        try {
          val selector = LeaderSelector(client, path, LeaderSelectorListenerAdapter(), userExecutor = executor)
          client.beforeLeaseGrant.store {
            throw StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("etcdserver: permission denied"))
          }
          val e = shouldThrow<EtcdRecipeRuntimeException> { selector.advertiseParticipation() }
          generateSequence(e as Throwable) { it.cause.takeIf { c -> c !== it } }
            .any { it is StatusRuntimeException && it.status.code == Status.Code.PERMISSION_DENIED } shouldBe true
        } finally {
          executor.shutdownNow()
        }
        etcd.deleteChildren(path)
      }
    }
  }
}
