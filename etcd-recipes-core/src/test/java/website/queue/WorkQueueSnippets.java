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

import io.etcd.jetcd.Client;
import io.etcd.recipes.queue.DistributedWorkQueue;
import io.etcd.recipes.queue.WorkQueueConfig;

import java.util.concurrent.TimeUnit;

import static io.etcd.recipes.common.ByteSequenceUtils.getAsString;

public class WorkQueueSnippets {

  public void basic(Client client) {
    // --8<-- [start:basic]
    try (DistributedWorkQueue queue = new DistributedWorkQueue(client, "/workqueue/jobs")) {
      queue.enqueue("job-1");

      // The item is claimed, not deleted: it survives in etcd until it is acked, so
      // a crash here redelivers it instead of losing it.
      DistributedWorkQueue.WorkItem item = queue.receive(30, TimeUnit.SECONDS);
      if (item != null) {
        System.out.println("Processing " + getAsString(item.getValue()) + " (attempt " + item.getAttempt() + ")");

        // ack() completes the item. false means the claim was already lost, so the
        // work may have been redone elsewhere — do not treat it as success.
        if (!item.ack()) {
          System.out.println("Lost the claim for " + item.getId());
        }
      }
    }
    // --8<-- [end:basic]
  }

  public void config(Client client) {
    // --8<-- [start:config]
    // Java sees only the (visibilityTimeoutSecs) and (visibilityTimeoutSecs,
    // maxDeliveries) constructors: sweepInterval is a kotlin.time.Duration, so its
    // constructor is not callable from Java. The default 30s sweep applies.
    WorkQueueConfig config = new WorkQueueConfig(30, 5);

    try (DistributedWorkQueue queue = new DistributedWorkQueue(client, "/workqueue/jobs", config)) {
      System.out.println("Consumer " + queue.getClientId() + " ready");
    }
    // --8<-- [end:config]
  }

  public void deadLetters(Client client) {
    // --8<-- [start:dead-letters]
    try (DistributedWorkQueue queue = new DistributedWorkQueue(client, "/workqueue/jobs")) {
      // Items that exhausted maxDeliveries land here instead of being redelivered
      // forever. Nothing drains this automatically — it is an operator surface.
      for (DistributedWorkQueue.DeadLetter dead : queue.deadLetters()) {
        System.out.println(
          "Dead letter " + dead.getId()
            + " after " + dead.getAttempts() + " attempts: "
            + getAsString(dead.getValue()));
      }

      // Replay one with a fresh attempt count, or drop it for good. Both return
      // false when no such dead letter exists.
      queue.requeueDeadLetter("1700000000000-abc");
      queue.purgeDeadLetter("1700000000001-def");
    }
    // --8<-- [end:dead-letters]
  }
}
