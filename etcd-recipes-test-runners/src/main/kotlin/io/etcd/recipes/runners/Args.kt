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

package io.etcd.recipes.runners

class Args(
  rawArgs: Array<String>,
) {
  private val map: Map<String, String> =
    rawArgs.associate { arg ->
      require(arg.startsWith("--") && arg.contains('=')) { "Expected --key=value, got: $arg" }
      val (key, value) = arg.removePrefix("--").split('=', limit = 2)
      key to value
    }

  fun require(key: String): String = map[key] ?: error("Missing required arg: --$key")

  fun optional(key: String): String? = map[key]

  fun int(key: String): Int = require(key).toInt()

  fun intOr(
    key: String,
    default: Int,
  ): Int = map[key]?.toInt() ?: default

  fun long(key: String): Long = require(key).toLong()

  fun longOr(
    key: String,
    default: Long,
  ): Long = map[key]?.toLong() ?: default
}
