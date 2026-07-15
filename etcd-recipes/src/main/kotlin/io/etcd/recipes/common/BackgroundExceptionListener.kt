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
 * Push-based notification of a background failure that a recipe would otherwise only
 * record in the pull-only [EtcdConnector.exceptions] list — a keep-alive death, an
 * abandoned watcher, a lost lock/leadership, or a user callback that threw.
 *
 * [context] is a short, human-readable source hint (typically the recipe's clientId or
 * path plus a kind, e.g. `"DistributedMutex:ab12cd3 keep-alive"`) so a single handler
 * registered across many recipes can attribute the failure. Listeners run on the
 * reporting recipe's own thread (a healer/dispatcher thread, never jetcd's event loop);
 * a listener that throws is logged and dropped, never re-recorded.
 */
fun interface BackgroundExceptionListener {
  fun onException(
    context: String,
    throwable: Throwable,
  )
}

/** Value form of a [BackgroundExceptionListener] notification, for the Flow surface. */
data class BackgroundException(
  val context: String,
  val throwable: Throwable,
)
