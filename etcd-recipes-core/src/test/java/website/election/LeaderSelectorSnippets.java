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

package website.election;

import io.etcd.jetcd.Client;
import io.etcd.recipes.common.ResilienceConfig;
import io.etcd.recipes.election.LeaderSelector;
import io.etcd.recipes.election.LeaderSelectorListener;
import io.etcd.recipes.election.LeaderSelectorListenerAdapter;
import io.etcd.recipes.election.Participant;

import java.util.List;

public class LeaderSelectorSnippets {

  public void basic(Client client) throws InterruptedException {
    // --8<-- [start:basic]
    LeaderSelectorListener listener =
      new LeaderSelectorListener() {
        @Override
        public void takeLeadership(LeaderSelector selector) {
          // Exactly one client across the cluster is inside this method at a time.
          // Leadership lasts exactly as long as this method runs.
          System.out.println(selector.getClientId() + " is the leader");
        }

        @Override
        public void relinquishLeadership(LeaderSelector selector) {
          System.out.println(selector.getClientId() + " is no longer the leader");
        }
      };

    try (LeaderSelector selector = new LeaderSelector(client, "/election/reports", listener)) {
      selector.start();
      // Blocks until this node's term is over.
      selector.waitOnLeadershipComplete();
    }
    // --8<-- [end:basic]
  }

  public void adapter(Client client) throws InterruptedException {
    // --8<-- [start:adapter]
    // The adapter no-ops both callbacks, so override only the one you care about.
    LeaderSelectorListener listener =
      new LeaderSelectorListenerAdapter() {
        @Override
        public void takeLeadership(LeaderSelector selector) {
          System.out.println(selector.getClientId() + " took leadership");
        }
      };

    try (LeaderSelector selector = new LeaderSelector(client, "/election/reports", listener)) {
      selector.start();
      selector.waitOnLeadershipComplete();
    }
    // --8<-- [end:adapter]
  }

  public void hold(Client client) throws InterruptedException {
    // --8<-- [start:hold]
    LeaderSelectorListener listener =
      new LeaderSelectorListenerAdapter() {
        @Override
        public void takeLeadership(LeaderSelector selector) {
          try {
            // Returning ends the term, so a node that means to lead "until shutdown"
            // parks here until close() or a lease loss releases it.
            selector.waitUntilFinished();
          } catch (InterruptedException e) {
            // A step-down interrupt lands here; restore the flag and stop leading.
            Thread.currentThread().interrupt();
          }
          if (!selector.isLeader()) {
            System.out.println("Term ended without a clean shutdown");
          }
        }
      };

    try (LeaderSelector selector = new LeaderSelector(client, "/election/reports", listener)) {
      selector.start();
      selector.waitOnLeadershipComplete();
    }
    // --8<-- [end:hold]
  }

  public void participants(Client client) {
    // --8<-- [start:participants]
    // Advisory snapshot of every candidate registered at the election path, and
    // which of them held the leader key at read time.
    List<Participant> participants = LeaderSelector.getParticipants(client, "/election/reports");
    for (Participant participant : participants) {
      System.out.println(participant.getClientId() + " isLeader=" + participant.isLeader());
    }
    // --8<-- [end:participants]
  }

  public void leaseLoss(Client client) throws InterruptedException {
    // --8<-- [start:lease-loss]
    LeaderSelectorListener listener =
      new LeaderSelectorListenerAdapter() {
        @Override
        public void relinquishLeadership(LeaderSelector selector) {
          // Runs on a clean hand-off and on a step-down after lease loss.
          System.out.println("Cleaning up leader-only resources");
        }
      };

    // Java has no named arguments, so every preceding parameter must be supplied
    // positionally to reach interruptOnLeaseLoss.
    try (LeaderSelector selector =
           new LeaderSelector(
             client,
             "/election/reports",
             listener,
             5L,                      // leaseTtlSecs
             null,                    // userExecutor
             "reporter-1",            // clientId
             ResilienceConfig.DEFAULT,
             false)) {                // interruptOnLeaseLoss (defaults to true)
      selector.addConnectionStateListener((newState, previous) ->
        System.out.println("Connection state: " + previous + " -> " + newState));
      selector.start();
      selector.waitOnLeadershipComplete();
    }
    // --8<-- [end:lease-loss]
  }
}
