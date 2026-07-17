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
import io.etcd.recipes.election.LeaderLatch;
import io.etcd.recipes.election.LeaderLatchListener;

import java.util.concurrent.TimeUnit;

public class LeaderLatchSnippets {

  public void basic(Client client) throws InterruptedException {
    // --8<-- [start:basic]
    try (LeaderLatch latch = new LeaderLatch(client, "/election/reports")) {
      latch.start();
      // Blocks until this node holds leadership; it then holds it until close().
      latch.await();
      System.out.println(latch.getClientId() + " is the leader");
      // Leaving the try-with-resources closes the latch, releases candidacy, and
      // a successor takes over.
    }
    // --8<-- [end:basic]
  }

  public void timedAwait(Client client) throws InterruptedException {
    // --8<-- [start:timed-await]
    try (LeaderLatch latch = new LeaderLatch(client, "/election/reports")) {
      latch.start();
      // Prefer the timed form: a parked no-arg await() is released only by an
      // interrupt or a timeout — close() does NOT unblock it. Java uses the
      // (long, TimeUnit) overload; the Duration one is Kotlin-facing.
      if (latch.await(30, TimeUnit.SECONDS)) {
        System.out.println("Leading");
      } else {
        System.out.println("Someone else is leading; carrying on as a follower");
      }
    }
    // --8<-- [end:timed-await]
  }

  public void hasLeadership(Client client) throws InterruptedException {
    // --8<-- [start:has-leadership]
    try (LeaderLatch latch = new LeaderLatch(client, "/election/reports")) {
      latch.start();
      // Advisory, and never a substitute for the work itself being safe: the
      // lease can expire between this read and the next line.
      if (latch.getHasLeadership()) {
        System.out.println("Running the leader-only sweep");
      }
    }
    // --8<-- [end:has-leadership]
  }

  public void listener(Client client) throws InterruptedException {
    // --8<-- [start:listener]
    LeaderLatchListener listener =
      new LeaderLatchListener() {
        @Override
        public void isLeader() {
          System.out.println("Gained leadership; starting the scheduler");
        }

        @Override
        public void notLeader() {
          // Fires on close() and on a step-down after lease loss.
          System.out.println("Lost leadership; stopping the scheduler");
        }
      };

    try (LeaderLatch latch = new LeaderLatch(client, "/election/reports")) {
      latch.addListener(listener);
      latch.start();
      latch.await(30, TimeUnit.SECONDS);
    }
    // --8<-- [end:listener]
  }

  public void leaseLoss(Client client) throws InterruptedException {
    // --8<-- [start:lease-loss]
    // Java has no named arguments, so every preceding parameter must be supplied
    // positionally to reach interruptOnLeaseLoss.
    try (LeaderLatch latch =
           new LeaderLatch(
             client,
             "/election/reports",
             5L,                       // leaseTtlSecs
             "reporter-1",             // clientId
             ResilienceConfig.DEFAULT,
             true)) {                  // interruptOnLeaseLoss (the default)
      latch.start();
      latch.await(30, TimeUnit.SECONDS);
      // On lease loss the latch steps down, fires notLeader(), and re-contests
      // with a fresh term rather than assuming it still leads.
      System.out.println("hasLeadership=" + latch.getHasLeadership());
    }
    // --8<-- [end:lease-loss]
  }
}
