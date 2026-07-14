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

import io.etcd.recipes.counter.DistributedAtomicLong

/** Suspending twin of [DistributedAtomicLong.get]. */
suspend fun DistributedAtomicLong.awaitGet(): Long = etcdInterruptible { get() }

/** Suspending twin of [DistributedAtomicLong.increment]. */
suspend fun DistributedAtomicLong.awaitIncrement(): Long = etcdInterruptible { increment() }

/** Suspending twin of [DistributedAtomicLong.decrement]. */
suspend fun DistributedAtomicLong.awaitDecrement(): Long = etcdInterruptible { decrement() }

/** Suspending twin of [DistributedAtomicLong.add]. */
suspend fun DistributedAtomicLong.awaitAdd(value: Long): Long = etcdInterruptible { add(value) }

/** Suspending twin of [DistributedAtomicLong.subtract]. */
suspend fun DistributedAtomicLong.awaitSubtract(value: Long): Long = etcdInterruptible { subtract(value) }
