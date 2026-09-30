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
import io.etcd.recipes.queue.DistributedWorkQueue
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration

/** Suspending twin of [DistributedWorkQueue.enqueue]. */
suspend fun DistributedWorkQueue.awaitEnqueue(value: ByteSequence): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of [DistributedWorkQueue.enqueue] for String values. */
suspend fun DistributedWorkQueue.awaitEnqueue(value: String): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of [DistributedWorkQueue.enqueue] for Int values. */
suspend fun DistributedWorkQueue.awaitEnqueue(value: Int): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of [DistributedWorkQueue.enqueue] for Long values. */
suspend fun DistributedWorkQueue.awaitEnqueue(value: Long): Unit = etcdInterruptible { enqueue(value) }

/** Suspending twin of the delayed [DistributedWorkQueue.enqueue]. */
suspend fun DistributedWorkQueue.awaitEnqueue(
  value: ByteSequence,
  delay: Duration,
): Unit = etcdInterruptible { enqueue(value, delay) }

/** Suspending twin of the delayed [DistributedWorkQueue.enqueue] for String values. */
suspend fun DistributedWorkQueue.awaitEnqueue(
  value: String,
  delay: Duration,
): Unit = etcdInterruptible { enqueue(value, delay) }

/** Suspending twin of [DistributedWorkQueue.enqueueAll] (one all-or-nothing transaction). */
suspend fun DistributedWorkQueue.awaitEnqueueAll(values: Collection<ByteSequence>): Unit =
  etcdInterruptible { enqueueAll(values) }

/**
 * Suspending twin of [DistributedWorkQueue.receive]: waits until an item can be
 * claimed. Cancellation interrupts the wait without leaving an orphan claim. A
 * cancellation that lands just as the claim succeeded returns the item to the queue
 * without spending a delivery attempt, since it never reached the caller.
 */
suspend fun DistributedWorkQueue.awaitReceive(): DistributedWorkQueue.WorkItem = claimUnclaimingOnCancel { receive() }!!

/** Suspending twin of the bounded [DistributedWorkQueue.receive]; see [awaitReceive]. */
suspend fun DistributedWorkQueue.awaitReceive(timeout: Duration): DistributedWorkQueue.WorkItem? =
  claimUnclaimingOnCancel { receive(timeout) }

/** Suspending twin of [DistributedWorkQueue.tryReceive]; see [awaitReceive]. */
suspend fun DistributedWorkQueue.awaitTryReceive(): DistributedWorkQueue.WorkItem? =
  claimUnclaimingOnCancel {
  tryReceive()
}

private suspend fun DistributedWorkQueue.claimUnclaimingOnCancel(
  claim: DistributedWorkQueue.() -> DistributedWorkQueue.WorkItem?,
): DistributedWorkQueue.WorkItem? = interruptibleAcquire(Dispatchers.IO, { claim() }, { item -> item?.unclaim() })

/** Suspending twin of [DistributedWorkQueue.WorkItem.ack]. */
suspend fun DistributedWorkQueue.WorkItem.awaitAck(): Boolean = etcdInterruptible { ack() }

/** Suspending twin of [DistributedWorkQueue.WorkItem.requeue]. */
suspend fun DistributedWorkQueue.WorkItem.awaitRequeue(): Boolean = etcdInterruptible { requeue() }

/** Suspending twin of [DistributedWorkQueue.deadLetters]. */
suspend fun DistributedWorkQueue.awaitDeadLetters(): List<DistributedWorkQueue.DeadLetter> =
  etcdInterruptible {
  deadLetters()
}

/** Suspending twin of [DistributedWorkQueue.requeueDeadLetter]. */
suspend fun DistributedWorkQueue.awaitRequeueDeadLetter(id: String): Boolean =
  etcdInterruptible { requeueDeadLetter(id) }

/** Suspending twin of [DistributedWorkQueue.purgeDeadLetter]. */
suspend fun DistributedWorkQueue.awaitPurgeDeadLetter(id: String): Boolean = etcdInterruptible { purgeDeadLetter(id) }
