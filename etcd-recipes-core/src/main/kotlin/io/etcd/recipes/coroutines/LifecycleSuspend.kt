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

package io.etcd.recipes.coroutines

import io.etcd.recipes.cache.NodeCache
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.cache.TypedPathChildrenCache
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.election.LeaderLatch
import io.etcd.recipes.election.LeaderObserver
import io.etcd.recipes.election.LeaderSelector
import io.etcd.recipes.keyvalue.TransientKeyValue
import io.etcd.recipes.keyvalue.TypedTransientKeyValue
import kotlin.time.Duration

/** Suspending twin of [TransientKeyValue.start] (blocks until the key is published). */
suspend fun TransientKeyValue.awaitStart(): TransientKeyValue = etcdInterruptible { start() }

/** Suspending twin of [DistributedAtomicLong.start]. */
suspend fun DistributedAtomicLong.awaitStart(): DistributedAtomicLong = etcdInterruptible { start() }

/** Suspending twin of [PathChildrenCache.start] (waits for the start to complete). */
suspend fun PathChildrenCache.awaitStart(buildInitial: Boolean = false): PathChildrenCache =
  etcdInterruptible { start(buildInitial) }

/** Suspending twin of [PathChildrenCache.start] with an explicit [PathChildrenCache.StartMode]. */
suspend fun PathChildrenCache.awaitStart(mode: PathChildrenCache.StartMode): PathChildrenCache =
  etcdInterruptible { start(mode) }

/** Suspending twin of [PathChildrenCache.waitOnStartComplete]. */
suspend fun PathChildrenCache.awaitStartComplete(): Boolean = etcdInterruptible { waitOnStartComplete() }

/** Suspending twin of the bounded [PathChildrenCache.waitOnStartComplete]. */
suspend fun PathChildrenCache.awaitStartComplete(timeout: Duration): Boolean =
  etcdInterruptible { waitOnStartComplete(timeout) }

/** Suspending twin of [LeaderSelector.start]. */
suspend fun LeaderSelector.awaitStart(): LeaderSelector = etcdInterruptible { start() }

/** Suspending twin of [LeaderSelector.waitOnLeadershipComplete]. */
suspend fun LeaderSelector.awaitLeadershipComplete(): Boolean = etcdInterruptible { waitOnLeadershipComplete() }

/** Suspending twin of the bounded [LeaderSelector.waitOnLeadershipComplete]. */
suspend fun LeaderSelector.awaitLeadershipComplete(timeout: Duration): Boolean =
  etcdInterruptible { waitOnLeadershipComplete(timeout) }

/** Suspending twin of [LeaderSelector.waitUntilFinished]. */
suspend fun LeaderSelector.awaitFinished(): Boolean = etcdInterruptible { waitUntilFinished() }

/** Suspending twin of the bounded [LeaderSelector.waitUntilFinished]. */
suspend fun LeaderSelector.awaitFinished(timeout: Duration): Boolean = etcdInterruptible { waitUntilFinished(timeout) }

/** Suspending twin of [LeaderLatch.start]. */
suspend fun LeaderLatch.awaitStart(): LeaderLatch = etcdInterruptible { start() }

/** Suspending twin of [LeaderLatch.await]: waits until this node holds leadership. */
suspend fun LeaderLatch.awaitLeadership(): Unit = etcdInterruptible { await() }

/** Suspending twin of the bounded [LeaderLatch.await]: false when [timeout] passes first. */
suspend fun LeaderLatch.awaitLeadership(timeout: Duration): Boolean = etcdInterruptible { await(timeout) }

/** Suspending twin of [LeaderObserver.start]. */
suspend fun LeaderObserver.awaitStart(): LeaderObserver = etcdInterruptible { start() }

/** Suspending twin of [NodeCache.start]. */
suspend fun <T> NodeCache<T>.awaitStart(): NodeCache<T> = etcdInterruptible { start() }

/** Suspending twin of [TypedPathChildrenCache.start] (waits for the start to complete). */
suspend fun <T> TypedPathChildrenCache<T>.awaitStart(buildInitial: Boolean = false): TypedPathChildrenCache<T> =
  etcdInterruptible { start(buildInitial) }

/** Suspending twin of [TypedPathChildrenCache.start] with an explicit [PathChildrenCache.StartMode]. */
suspend fun <T> TypedPathChildrenCache<T>.awaitStart(mode: PathChildrenCache.StartMode): TypedPathChildrenCache<T> =
  etcdInterruptible { start(mode) }

/** Suspending twin of [TypedPathChildrenCache.waitOnStartComplete]. */
suspend fun <T> TypedPathChildrenCache<T>.awaitStartComplete(): Boolean = etcdInterruptible { waitOnStartComplete() }

/** Suspending twin of the bounded [TypedPathChildrenCache.waitOnStartComplete]. */
suspend fun <T> TypedPathChildrenCache<T>.awaitStartComplete(timeout: Duration): Boolean =
  etcdInterruptible { waitOnStartComplete(timeout) }

/** Suspending twin of [TypedTransientKeyValue.start] (returns once the key is published). */
suspend fun <T> TypedTransientKeyValue<T>.awaitStart(): TypedTransientKeyValue<T> = etcdInterruptible { start() }
