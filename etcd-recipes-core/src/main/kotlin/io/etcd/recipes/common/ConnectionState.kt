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
 * Coarse connection health of a recipe, derived passively from its own watch and
 * lease streams (a connector with no active watch or lease reports nothing).
 *
 * - [CONNECTED]: initial state; no failures observed yet.
 * - [SUSPENDED]: a stream reported an error; recovery (jetcd's or the recipe
 *   layer's) is in progress.
 * - [RECONNECTED]: a stream was re-established after a suspension; for leases,
 *   ownership was re-established too.
 * - [LOST]: a lease expired (ownership may have been lost — e.g. a leader should
 *   consider stepping down), or recovery was abandoned.
 */
enum class ConnectionState {
  CONNECTED,
  SUSPENDED,
  RECONNECTED,
  LOST,
}

fun interface ConnectionStateListener {
  fun stateChanged(
    newState: ConnectionState,
    previous: ConnectionState,
  )
}
