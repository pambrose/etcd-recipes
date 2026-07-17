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

package io.etcd.recipes.election

interface LeaderListener {
  fun takeLeadership(leaderName: String)

  fun relinquishLeadership()

  /**
   * Invoked when a [takeLeadership] or [relinquishLeadership] callback throws while being
   * dispatched from the leader watch loop. The default is a no-op, so existing Kotlin and Java
   * implementors remain source- and binary-compatible; override it to surface the failure
   * programmatically. The watch loop continues running after this is called.
   */
  fun onError(e: Throwable) {}
}
