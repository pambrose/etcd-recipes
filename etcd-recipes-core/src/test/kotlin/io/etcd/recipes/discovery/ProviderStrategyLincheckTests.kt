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

package io.etcd.recipes.discovery

import io.kotest.core.spec.style.StringSpec
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation

private val A = serviceInstance("svc", "a")
private val B = serviceInstance("svc", "b")
private val C = serviceInstance("svc", "c")

// A deterministic delegate, so the only state under test is the strategy's own
private val First = ProviderStrategy { it.firstOrNull() }

/** [StickyStrategy]'s operations, for Lincheck. Results are payloads, comparable across runs. */
class StickyStrategyOperations {
  private val strategy = StickyStrategy(First)

  @Operation
  fun selectFromAB() = strategy.select([A, B])?.jsonPayload

  @Operation
  fun selectFromB() = strategy.select([B])?.jsonPayload

  @Operation
  fun selectFromNone() = strategy.select([])?.jsonPayload
}

/** [RoundRobinStrategy]'s operations, for Lincheck. */
class RoundRobinStrategyOperations {
  private val strategy = RoundRobinStrategy()

  @Operation
  fun selectFromABC() = strategy.select([A, B, C])?.jsonPayload

  @Operation
  fun selectFromAB() = strategy.select([A, B])?.jsonPayload
}

/**
 * The stateful provider strategies are shared by every thread that calls
 * `ServiceProvider.getInstance()`, so concurrent selections must behave as if they ran one
 * at a time. Lincheck's model checker explores the interleavings of small concurrent
 * scenarios and reports any whose results no sequential order could produce.
 */
class ProviderStrategyLincheckTests : StringSpec() {
  private fun modelChecking() = ModelCheckingOptions().iterations(30).invocationsPerIteration(500)

  init {
    "concurrent StickyStrategy selections are linearizable" {
      modelChecking().check(StickyStrategyOperations::class)
    }

    "concurrent RoundRobinStrategy selections are linearizable" {
      modelChecking().check(RoundRobinStrategyOperations::class)
    }
  }
}
