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
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe

/**
 * Pure selection semantics of the load-balancing strategies — no etcd. Instances carry
 * distinct payloads so value-equality distinguishes them.
 */
class ProviderStrategyTests : StringSpec() {
  private fun instances(n: Int) = (1..n).map { serviceInstance("svc", "payload-$it") }

  init {
    "RandomStrategy returns an element of a non-empty list" {
      val list = instances(3)
      repeat(20) { RandomStrategy.select(list) shouldBeIn list }
    }

    "RandomStrategy returns null on an empty list" {
      RandomStrategy.select(emptyList()) shouldBe null
    }

    "RoundRobinStrategy cycles through every instance in order and wraps" {
      val list = instances(3)
      val strategy = RoundRobinStrategy()
      val picks = (1..6).map { strategy.select(list) }
      picks shouldBe listOf(list[0], list[1], list[2], list[0], list[1], list[2])
    }

    "RoundRobinStrategy stays valid when the list shrinks" {
      val three = instances(3)
      val strategy = RoundRobinStrategy()
      strategy.select(three) // advance the cursor
      strategy.select(three)
      // List drops to 1: index must stay in range, never throw.
      val one = listOf(three[0])
      repeat(5) { strategy.select(one) shouldBe three[0] }
    }

    "RoundRobinStrategy returns null on an empty list" {
      RoundRobinStrategy().select(emptyList()) shouldBe null
    }

    "StickyStrategy returns the same instance until it leaves, then re-picks" {
      val list = instances(3)
      val strategy = StickyStrategy()
      val first = strategy.select(list)!!
      repeat(5) { strategy.select(list) shouldBe first } // sticks

      // Remove the stuck instance: a different one is selected and then sticks.
      val without = list.filter { it != first }
      val second = strategy.select(without)!!
      (second != first) shouldBe true
      repeat(5) { strategy.select(without) shouldBe second }
    }

    "StickyStrategy returns null on empty and re-picks after the list refills" {
      val list = instances(2)
      val strategy = StickyStrategy()
      strategy.select(list)
      strategy.select(emptyList()) shouldBe null
      strategy.select(list) shouldBeIn list
    }
  }
}
