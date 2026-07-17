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

import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Skips the test cleanly when run without `-PuseTestcontainers`.
 *
 * Fault-injection tests mutate cluster-wide etcd state (compaction, container
 * pause/restart), which would disturb other test classes sharing the local etcd at
 * `localhost:2379`. Under Testcontainers each forked test JVM owns a private etcd
 * container (`forkEvery=1` + per-JVM lazy fixture), so faults are fully isolated.
 */
internal fun assumeFaultInjection() {
  assumeTrue(
    System.getProperty("etcd.recipes.testcontainers") == "true",
    "Fault-injection tests require -PuseTestcontainers (they must not disturb a shared local etcd)",
  )
}
