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

package io.etcd.recipes.examples.election;

import io.etcd.jetcd.Client;
import io.etcd.recipes.election.LeaderSelector;
import io.etcd.recipes.election.LeaderSelectorListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static io.etcd.recipes.common.ClientUtils.connectToEtcd;

public class LeaderSelectorExample {

  public static void main(String[] args) throws InterruptedException {
    List<String> urls = List.of("http://localhost:2379");
    String electionPath = "/election/LeaderSelectorExample";
    int count = 5;

    LeaderSelectorListener listener =
      new LeaderSelectorListener() {
        @Override
        public void takeLeadership(LeaderSelector selector) {
          System.out.println(selector.getClientId() + " elected leader");
          long pause = ThreadLocalRandom.current().nextLong(5);
          try {
            TimeUnit.SECONDS.sleep(pause);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          System.out.printf("%s surrendering after %s seconds%n", selector.getClientId(), pause);
        }

        @Override
        public void relinquishLeadership(LeaderSelector selector) {
          System.out.printf("%s relinquished leadership%n", selector.getClientId());
        }
      };

    try (Client client = connectToEtcd(urls)) {
      System.out.println("Single leader is created and repeatedly runs for election");
      try (LeaderSelector selector = new LeaderSelector(client, electionPath, listener)) {
        for (int i = 0; i < count; i++) {
          selector.start();

          selector.waitOnLeadershipComplete();
        }
      }

      System.out.println("\nMultiple leaders are created and each runs for election once");
      List<LeaderSelector> selectors = new ArrayList<>();
      for (int i = 0; i < count; i++)
        selectors.add(new LeaderSelector(client, electionPath, listener));

      for (LeaderSelector selector : selectors)
        selector.start();

      System.out.printf("Participants: %s%n", LeaderSelector.getParticipants(client, electionPath));

      for (LeaderSelector selector : selectors)
        selector.waitOnLeadershipComplete();

      for (LeaderSelector selector : selectors)
        selector.close();
    }
  }
}
