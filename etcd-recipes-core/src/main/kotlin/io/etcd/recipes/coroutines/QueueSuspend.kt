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
import io.etcd.jetcd.KeyValue
import io.etcd.recipes.queue.AbstractQueue
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.DistributedQueue
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Suspending twin of [AbstractQueue.dequeue]: waits until an item is available.
 * Cancellation interrupts the wait and consumes nothing. A cancellation that lands just
 * as the take succeeded puts the item back under its original key. A
 * [io.etcd.recipes.queue.DistributedPriorityQueue] item keeps its place; a
 * [io.etcd.recipes.queue.DistributedQueue] orders by commit revision, so the item
 * rejoins at the tail. If etcd is unreachable at that moment, the item is lost and the
 * failure recorded. For at-least-once delivery, use
 * [io.etcd.recipes.queue.DistributedWorkQueue].
 * Compose with `withTimeout { }` or use the bounded overload.
 */
suspend fun AbstractQueue.receive(): ByteSequence = takeRestoringOnCancel { takeEntry(null) }!!

/** Suspending twin of [AbstractQueue.poll]: an item, or null once [timeout] elapses. See [receive]. */
suspend fun AbstractQueue.receive(timeout: Duration): ByteSequence? {
  require(timeout > Duration.ZERO) { "Timeout must be positive: $timeout" }
  val deadline = TimeSource.Monotonic.markNow() + timeout
  return takeRestoringOnCancel { takeEntry(deadline) }
}

/** Suspending twin of [AbstractQueue.tryDequeue] (non-blocking claim; RPCs still run off-thread). See [receive]. */
suspend fun AbstractQueue.awaitTryDequeue(): ByteSequence? = takeRestoringOnCancel { tryDequeueEntry() }

private suspend fun AbstractQueue.takeRestoringOnCancel(take: AbstractQueue.() -> KeyValue?): ByteSequence? =
  interruptibleAcquire(Dispatchers.IO, { take() }, { entry -> entry?.let { restoreTaken(it) } })?.value

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
