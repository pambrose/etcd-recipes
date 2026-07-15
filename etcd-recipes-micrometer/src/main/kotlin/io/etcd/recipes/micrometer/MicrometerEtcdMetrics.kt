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
import io.micrometer.core.instrument.DistributionSummary
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
 * - `etcd.keepalive` — a counter of self-healing-lease events, tagged `kind`;
 * - `etcd.lock.wait` — a [Timer] of lock/permit acquisition waits, tagged `outcome`
 *   (`acquired`/`timeout`);
 * - `etcd.lock.hold` — a [Timer] of lock/permit hold durations;
 * - `etcd.election.transitions` — a counter of leadership changes, tagged `transition`
 *   (`acquired`/`relinquished`);
 * - `etcd.queue` — a [Timer] of queue operations, tagged `op` (`enqueue`/`dequeue`);
 * - `etcd.cache.sync` — a [Timer] of cache snapshot loads, and `etcd.cache.size` a
 *   [DistributionSummary] of the resulting entry counts.
 *
 * Tag cardinality is kept low on purpose: the etcd key, lock path, and lease id are used only
 * for the (low-cardinality) `operation`/`kind`/`outcome` dimensions, never as tags themselves.
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

  override fun recordLockWait(
    path: String,
    duration: Duration,
    acquired: Boolean,
  ) {
    Timer.builder("etcd.lock.wait")
      .tag("outcome", if (acquired) "acquired" else "timeout")
      .register(registry)
      .record(duration.toJavaDuration())
  }

  override fun recordLockHold(
    path: String,
    duration: Duration,
  ) {
    Timer.builder("etcd.lock.hold")
      .register(registry)
      .record(duration.toJavaDuration())
  }

  override fun incrementLeadershipTransition(
    path: String,
    becameLeader: Boolean,
  ) {
    registry.counter("etcd.election.transitions", "transition", if (becameLeader) "acquired" else "relinquished")
      .increment()
  }

  override fun recordQueue(
    op: String,
    path: String,
    duration: Duration,
  ) {
    Timer.builder("etcd.queue")
      .tag("op", op)
      .register(registry)
      .record(duration.toJavaDuration())
  }

  override fun recordCacheSync(
    path: String,
    duration: Duration,
    size: Int,
  ) {
    Timer.builder("etcd.cache.sync").register(registry).record(duration.toJavaDuration())
    DistributionSummary.builder("etcd.cache.size").register(registry).record(size.toDouble())
  }
}
