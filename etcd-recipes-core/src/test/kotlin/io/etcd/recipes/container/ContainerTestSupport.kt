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

package io.etcd.recipes.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.GenericContainer
import java.util.UUID

/**
 * Skips the test cleanly when run without `-PuseTestcontainers`. The container fixtures
 * require Docker; a default `./gradlew check` should pass with the existing thread tests
 * and quietly skip the container variants.
 */
internal fun assumeContainerMode() {
  assumeTrue(
    System.getProperty("etcd.recipes.testcontainers") == "true",
    "Container tests require -PuseTestcontainers",
  )
}

internal fun newTestId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"

/** Start the given containers in parallel and stop them all when [block] returns or throws. */
internal fun <R> useContainers(
  containers: List<GenericContainer<*>>,
  block: () -> R,
): R {
  runBlocking(Dispatchers.IO) {
    containers.map { async { it.start() } }.awaitAll()
  }
  try {
    return block()
  } finally {
    runBlocking(Dispatchers.IO) {
      containers.map { async { runCatching { it.stop() } } }.awaitAll()
    }
  }
}
