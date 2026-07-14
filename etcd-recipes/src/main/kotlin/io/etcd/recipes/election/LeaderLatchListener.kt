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

package io.etcd.recipes.election

/**
 * Notified when a [LeaderLatch] gains or loses leadership. Both methods default to
 * no-ops, so implementors can override only the transition they care about (and
 * remain Java-friendly). Callbacks are dispatched on the latch's notification thread,
 * in `isLeader` → `notLeader` order; they must not block for long.
 */
interface LeaderLatchListener {
  /** Invoked when this latch acquires leadership. */
  fun isLeader() {}

  /** Invoked when this latch loses or relinquishes leadership. */
  fun notLeader() {}
}
