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

import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The interruptible bridge converts a wrapped mid-RPC interrupt into cancellation.
 * The blocking RPC engine re-sets the interrupt flag before wrapping, so a second RPC
 * on the same thread can wrap the InterruptedException a second time — the bridge must
 * find it anywhere in the cause chain, at any depth. No etcd needed.
 */
class BridgesTests : StringSpec() {
  init {
    "a directly wrapped interrupt becomes CancellationException" {
      runBlocking {
        val wrapped = EtcdRecipeRuntimeException("getResponse interrupted", InterruptedException())
        shouldThrow<CancellationException> {
          interruptibleOn(Dispatchers.IO) { throw wrapped }
        }
      }
    }

    "a doubly wrapped interrupt becomes CancellationException" {
      runBlocking {
        val doubleWrapped =
          EtcdRecipeRuntimeException(
            "getResponse interrupted",
            EtcdRecipeRuntimeException("getResponse interrupted", InterruptedException()),
          )
        shouldThrow<CancellationException> {
          interruptibleOn(Dispatchers.IO) { throw doubleWrapped }
        }
      }
    }

    "an EtcdRecipeRuntimeException with no interrupt in the chain propagates unchanged" {
      runBlocking {
        val real = EtcdRecipeRuntimeException("real failure", IllegalStateException("boom"))
        val thrown =
          shouldThrow<EtcdRecipeRuntimeException> {
            interruptibleOn(Dispatchers.IO) { throw real }
          }
        thrown.message shouldBe "real failure"
      }
    }
  }
}
