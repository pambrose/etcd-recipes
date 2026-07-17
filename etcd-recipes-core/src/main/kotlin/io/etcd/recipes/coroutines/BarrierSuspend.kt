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

import io.etcd.recipes.barrier.DistributedBarrier
import io.etcd.recipes.barrier.DistributedBarrierWithCount
import io.etcd.recipes.barrier.DistributedDoubleBarrier
import kotlin.time.Duration

/** Suspending twin of [DistributedBarrier.setBarrier]. */
suspend fun DistributedBarrier.awaitSetBarrier(): Boolean = etcdInterruptible { setBarrier() }

/** Suspending twin of [DistributedBarrier.removeBarrier]. */
suspend fun DistributedBarrier.awaitRemoveBarrier(): Boolean = etcdInterruptible { removeBarrier() }

/**
 * Suspending twin of [DistributedBarrier.waitOnBarrier]: waits until the barrier is
 * removed. Cancellation interrupts the wait cleanly.
 */
suspend fun DistributedBarrier.await(): Boolean = etcdInterruptible { waitOnBarrier() }

/** Suspending twin of the bounded [DistributedBarrier.waitOnBarrier]. */
suspend fun DistributedBarrier.await(timeout: Duration): Boolean = etcdInterruptible { waitOnBarrier(timeout) }

/**
 * Suspending twin of [DistributedBarrierWithCount.waitOnBarrier]: waits until
 * [DistributedBarrierWithCount.memberCount] waiters have arrived. Cancellation
 * interrupts the wait and revokes this waiter's entry, so it no longer counts
 * toward the barrier.
 */
suspend fun DistributedBarrierWithCount.await(): Boolean = etcdInterruptible { waitOnBarrier() }

/** Suspending twin of the bounded [DistributedBarrierWithCount.waitOnBarrier]. */
suspend fun DistributedBarrierWithCount.await(timeout: Duration): Boolean = etcdInterruptible { waitOnBarrier(timeout) }

/** Suspending twin of [DistributedDoubleBarrier.enter]. */
suspend fun DistributedDoubleBarrier.awaitEnter(): Boolean = etcdInterruptible { enter() }

/** Suspending twin of the bounded [DistributedDoubleBarrier.enter]. */
suspend fun DistributedDoubleBarrier.awaitEnter(timeout: Duration): Boolean = etcdInterruptible { enter(timeout) }

/** Suspending twin of [DistributedDoubleBarrier.leave]. */
suspend fun DistributedDoubleBarrier.awaitLeave(): Boolean = etcdInterruptible { leave() }

/** Suspending twin of the bounded [DistributedDoubleBarrier.leave]. */
suspend fun DistributedDoubleBarrier.awaitLeave(timeout: Duration): Boolean = etcdInterruptible { leave(timeout) }
