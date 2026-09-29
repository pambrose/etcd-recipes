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

package io.etcd.recipes.barrier

import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.HookedClient
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.urls
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit

/**
 * A barrier must report an infrastructure failure as one. The barriers caught every
 * `EtcdRecipeRuntimeException` from lease setup and reported it as a lost CAS:
 * `setBarrier()` returned `false` ("another client holds the barrier") and
 * `waitOnBarrier` threw "Failed to set waitingPath" (documented as a key collision) when
 * etcd had in fact refused or failed the lease grant.
 */
class BarrierInfrastructureFailureTests : StringSpec() {
  private val base = "/barriers/${javaClass.simpleName}"

  private fun permissionDenied() =
    StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("etcdserver: permission denied"))

  private fun Throwable.hasStatus(code: Status.Code) =
    generateSequence(this) { it.cause.takeIf { c -> c !== it } }.any {
      it is StatusRuntimeException &&
      it.status.code == code
    }

  init {
    "setBarrier reports a failed lease grant instead of claiming the barrier is held" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/set"
        etcd.deleteKey(path)
        val client = HookedClient(etcd)

        DistributedBarrier(client, path).use { barrier ->
          client.beforeLeaseGrant.store { throw permissionDenied() }
          val e = shouldThrow<EtcdRecipeRuntimeException> { barrier.setBarrier() }
          e.hasStatus(Status.Code.PERMISSION_DENIED) shouldBe true
        }
        etcd.isKeyPresent(path) shouldBe false
      }
    }

    "waitOnBarrier reports a failed lease grant instead of a waiting-key collision" {
      connectToEtcd(urls) { etcd ->
        val path = "$base/wait"
        etcd.deleteChildren(path)
        val client = HookedClient(etcd)

        DistributedBarrierWithCount(client, path, memberCount = 2).use { barrier ->
          client.beforeLeaseGrant.store { throw permissionDenied() }
          val e = shouldThrow<EtcdRecipeRuntimeException> { barrier.waitOnBarrier(10, TimeUnit.SECONDS) }
          e.hasStatus(Status.Code.PERMISSION_DENIED) shouldBe true
        }
        etcd.deleteChildren(path)
      }
    }
  }
}
