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

package website.queue;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.recipes.queue.DistributedQueue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.etcd.recipes.common.ByteSequenceUtils.getAsByteSequence;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsString;

public class QueueSnippets {

  public void basic(Client client) {
    // --8<-- [start:basic]
    try (DistributedQueue queue = new DistributedQueue(client, "/queues/orders")) {
      queue.enqueue("order-1");

      // Blocks until an item is available. Exactly one consumer across the cluster
      // wins each item: the take is a CAS delete guarded on the item's mod revision.
      ByteSequence value = queue.dequeue();
      System.out.println("Dequeued " + getAsString(value));
    }
    // --8<-- [end:basic]
  }

  public void tryDequeue(Client client) {
    // --8<-- [start:try-dequeue]
    try (DistributedQueue queue = new DistributedQueue(client, "/queues/orders")) {
      // Non-blocking: null the moment the queue is found empty.
      ByteSequence immediate = queue.tryDequeue();

      // Java uses the (long, TimeUnit) overload; the Duration one is Kotlin-facing.
      ByteSequence waited = queue.poll(5, TimeUnit.SECONDS);

      System.out.println("tryDequeue=" + immediate + ", poll=" + waited);
    }
    // --8<-- [end:try-dequeue]
  }

  public void enqueueAll(Client client) {
    // --8<-- [start:enqueue-all]
    try (DistributedQueue queue = new DistributedQueue(client, "/queues/orders")) {
      // One transaction: either every value lands or none does. The keys embed the
      // argument index, so within the batch consumers see them in argument order.
      queue.enqueueAll(
        List.of(
          getAsByteSequence("order-1"),
          getAsByteSequence("order-2"),
          getAsByteSequence("order-3")));

      // getSize() costs a range-count RPC on every read — it is not a cached counter.
      System.out.println("Depth: " + queue.getSize());
    }
    // --8<-- [end:enqueue-all]
  }
}
