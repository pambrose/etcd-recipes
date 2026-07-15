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

package io.etcd.recipes.micrometer

import io.etcd.recipes.common.EtcdMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A Micrometer-backed [EtcdMetrics]. Install it on any recipe with
 * `ResilienceConfig.withMetrics(MicrometerEtcdMetrics(registry))`.
 *
 * Meters registered (all sharing whatever common tags [registry] carries):
 * - `etcd.rpc` — a [Timer] per blocking RPC, tagged `operation` (the op name without its key
 *   argument) and `outcome` (`success`/`failure`);
 * - `etcd.rpc.retries` — a counter of extra attempts beyond the first, tagged `operation`;
 * - `etcd.watch.recovery` — a counter of resilient-watcher transitions, tagged `kind`;
 * - `etcd.keepalive` — a counter of self-healing-lease events, tagged `kind`.
 *
 * Tag cardinality is kept low on purpose: the etcd key and lease id are used only for the
 * (low-cardinality) `operation`/`kind` dimensions, never as tags themselves.
 */
class MicrometerEtcdMetrics(
  private val registry: MeterRegistry,
) : EtcdMetrics {
  override fun recordRpc(
    opName: String,
    duration: Duration,
    attempts: Int,
    failed: Boolean,
  ) {
    val operation = opName.substringBefore('(')
    Timer.builder("etcd.rpc")
      .tag("operation", operation)
      .tag("outcome", if (failed) "failure" else "success")
      .register(registry)
      .record(duration.toJavaDuration())
    if (attempts > 1) {
      registry.counter("etcd.rpc.retries", "operation", operation).increment((attempts - 1).toDouble())
    }
  }

  override fun incrementWatchRecovery(
    kind: String,
    key: String,
  ) {
    registry.counter("etcd.watch.recovery", "kind", kind).increment()
  }

  override fun incrementKeepAlive(
    kind: String,
    leaseId: Long,
  ) {
    registry.counter("etcd.keepalive", "kind", kind).increment()
  }
}
