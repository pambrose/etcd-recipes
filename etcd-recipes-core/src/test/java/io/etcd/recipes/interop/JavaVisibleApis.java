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

package io.etcd.recipes.interop;

import io.etcd.jetcd.Client;
import io.etcd.recipes.common.EtcdRecipes;
import io.etcd.recipes.common.ResilienceConfig;
import io.etcd.recipes.election.LeaderLatch;
import io.etcd.recipes.queue.DistributedPriorityQueue;

import java.util.concurrent.TimeUnit;

/**
 * A compile-time check that APIs taking a {@code kotlin.time.Duration} stay reachable from Java.
 * Such a parameter mangles the member's JVM name into one Java can't spell, which hides it
 * silently: no Kotlin test notices, but this file stops compiling (CI compiles every test
 * source set). Never run.
 */
@SuppressWarnings("unused")
final class JavaVisibleApis {
  private JavaVisibleApis() {
  }

  static void priorityQueues(Client client) {
    EtcdRecipes recipes = new EtcdRecipes(client);
    DistributedPriorityQueue plain = recipes.distributedPriorityQueue("/queues/jobs");
    DistributedPriorityQueue paced = recipes.distributedPriorityQueue("/queues/jobs", 50, TimeUnit.MILLISECONDS);
    DistributedPriorityQueue direct = new DistributedPriorityQueue(client, "/queues/jobs", 50, TimeUnit.MILLISECONDS);
    DistributedPriorityQueue configured =
      new DistributedPriorityQueue(client, "/queues/jobs", 50, TimeUnit.MILLISECONDS, ResilienceConfig.DEFAULT);
  }

  static void leaderLatchCloseJoinTimeout(Client client) {
    LeaderLatch latch =
      new LeaderLatch(client, "/election/jobs", 10, "worker-1", ResilienceConfig.DEFAULT, true, 5, TimeUnit.SECONDS);
  }
}
