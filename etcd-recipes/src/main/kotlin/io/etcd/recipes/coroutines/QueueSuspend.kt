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

import io.etcd.jetcd.ByteSequence
import io.etcd.recipes.queue.AbstractQueue
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.DistributedQueue
import kotlin.time.Duration

/**
 * Suspending twin of [AbstractQueue.dequeue]: waits until an item is available.
 * Cancellation interrupts the wait and leaves the queue intact — no item is
 * consumed. Compose with `withTimeout { }` or use the bounded overload.
 */
suspend fun AbstractQueue.receive(): ByteSequence = etcdInterruptible { dequeue() }

/** Suspending twin of [AbstractQueue.poll]: an item, or null once [timeout] elapses. */
suspend fun AbstractQueue.receive(timeout: Duration): ByteSequence? = etcdInterruptible { poll(timeout) }

/** Suspending twin of [AbstractQueue.tryDequeue] (non-blocking claim; RPCs still run off-thread). */
suspend fun AbstractQueue.awaitTryDequeue(): ByteSequence? = etcdInterruptible { tryDequeue() }

/** Suspending twin of [DistributedQueue.enqueue]. */
suspend fun DistributedQueue.awaitEnqueue(value: ByteSequence): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of [DistributedQueue.enqueue] for String values. */
suspend fun DistributedQueue.awaitEnqueue(value: String): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of [DistributedQueue.enqueue] for Int values. */
suspend fun DistributedQueue.awaitEnqueue(value: Int): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of [DistributedQueue.enqueue] for Long values. */
suspend fun DistributedQueue.awaitEnqueue(value: Long): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of [DistributedQueue.enqueueAll] (one all-or-nothing transaction). */
suspend fun DistributedQueue.awaitEnqueueAll(values: Collection<ByteSequence>): Unit =
  etcdInterruptible { enqueueAll(values) }

/** Suspending twin of [DistributedPriorityQueue.enqueue]. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: ByteSequence,
  priority: UShort,
): Unit = etcdInterruptible { enqueue(value, priority) }

/** Suspending twin of [DistributedPriorityQueue.enqueue] for String values. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: String,
  priority: UShort,
): Unit = etcdInterruptible { enqueue(value, priority) }

/** Suspending twin of [DistributedPriorityQueue.enqueue] for Int values. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: Int,
  priority: UShort,
): Unit = etcdInterruptible { enqueue(value, priority) }

/** Suspending twin of [DistributedPriorityQueue.enqueue] for Long values. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: Long,
  priority: UShort,
): Unit = etcdInterruptible { enqueue(value, priority) }

/** Suspending twin of [DistributedPriorityQueue.enqueue] with an Int priority. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: ByteSequence,
  priority: Int,
): Unit = etcdInterruptible { enqueue(value, priority) }

/** Suspending twin of [DistributedPriorityQueue.enqueue] for String values with an Int priority. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: String,
  priority: Int,
): Unit = etcdInterruptible { enqueue(value, priority) }

/** Suspending twin of [DistributedPriorityQueue.enqueue] for Int values with an Int priority. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: Int,
  priority: Int,
): Unit = etcdInterruptible { enqueue(value, priority) }

/** Suspending twin of [DistributedPriorityQueue.enqueue] for Long values with an Int priority. */
suspend fun DistributedPriorityQueue.awaitEnqueue(
  value: Long,
  priority: Int,
): Unit = etcdInterruptible { enqueue(value, priority) }
