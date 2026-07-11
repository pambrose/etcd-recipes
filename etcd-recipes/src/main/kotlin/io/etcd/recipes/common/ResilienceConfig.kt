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

package io.etcd.recipes.common

/**
 * Bundles the resilience configuration a recipe applies to its etcd interactions.
 * Resilience is ON by default; pass [DISABLED] to restore the pre-0.12 behavior
 * (fatally dead watchers stay dead, silently).
 */
data class ResilienceConfig(
  val watch: WatchResilience = WatchResilience.DEFAULT,
) {
  companion object {
    @JvmField
    val DEFAULT = ResilienceConfig()

    @JvmField
    val DISABLED = ResilienceConfig(watch = WatchResilience.DISABLED)
  }
}
