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

@file:Suppress("EmptyFunctionBlock")

package io.etcd.recipes.common

import kotlin.time.Duration

/**
 * Instrumentation sink the library calls at its key seams. This is a dependency-free SPI:
 * the library ships only the no-op [EtcdMetrics.NoOp]. Install a backend with
 * [ResilienceConfig.withMetrics] — either a Micrometer binding (a separate module) or your
 * own implementation. Every method has an empty default body, so a backend overrides only the
 * seams it cares about and callers pay nothing when metrics are off.
 *
 * Implementations must be thread-safe and cheap: seams are hit from RPC-caller threads, the
 * watch dispatcher, and lease-healer threads. `key`/`leaseId` are passed for context; a
 * backend should keep tag cardinality low (prefer the `kind`/outcome dimensions).
 *
 * Currently instruments the three connection funnels every recipe shares; recipe-level seams
 * (lock wait/hold, queue, election, cache) are added as they are instrumented.
 */
interface EtcdMetrics {
  /** One completed blocking RPC: total [duration], number of [attempts], and whether it ultimately [failed]. */
  fun recordRpc(
    opName: String,
    duration: Duration,
    attempts: Int,
    failed: Boolean,
  ) {}

  /** A resilient-watcher recovery transition; [kind] is one of suspended / resubscribed / resynced / failed. */
  fun incrementWatchRecovery(
    kind: String,
    key: String,
  ) {}

  /** A self-healing-lease event; [kind] is one of renewal / suspended / expired / restored / failed. */
  fun incrementKeepAlive(
    kind: String,
    leaseId: Long,
  ) {}

  /** Time a caller spent trying to acquire a lock/permit at [path], and whether it ultimately [acquired] it. */
  fun recordLockWait(
    path: String,
    duration: Duration,
    acquired: Boolean,
  ) {}

  /** How long a lock/permit at [path] was held, from grant to release. */
  fun recordLockHold(
    path: String,
    duration: Duration,
  ) {}

  /** A leadership transition for the election [path]: [becameLeader] true on take, false on relinquish. */
  fun incrementLeadershipTransition(
    path: String,
    becameLeader: Boolean,
  ) {}

  companion object {
    /** The default: records nothing. Selected unless a backend is installed via [ResilienceConfig.withMetrics]. */
    val NoOp: EtcdMetrics = object : EtcdMetrics {}
  }
}
