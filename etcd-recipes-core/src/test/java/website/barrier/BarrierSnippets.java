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

package website.barrier;

import io.etcd.jetcd.Client;
import io.etcd.recipes.barrier.DistributedBarrier;
import io.etcd.recipes.barrier.DistributedBarrierWithCount;
import io.etcd.recipes.barrier.DistributedDoubleBarrier;
import io.etcd.recipes.common.EtcdRecipeException;

import java.util.concurrent.TimeUnit;

public class BarrierSnippets {

  public void set(Client client) {
    // --8<-- [start:set]
    try (DistributedBarrier barrier = new DistributedBarrier(client, "/barriers/import")) {
      // false means another client already holds the barrier — this instance did
      // not arm it, and must not assume it may lift it.
      if (barrier.setBarrier()) {
        System.out.println("Barrier armed; every waiter blocks");

        // ... do the work that the waiters must not race ...

        // Releases every waiter at once.
        barrier.removeBarrier();
      } else {
        System.out.println("Someone else armed it");
      }
    }
    // --8<-- [end:set]
  }

  public void waitOn(Client client) throws InterruptedException {
    // --8<-- [start:wait]
    try (DistributedBarrier barrier = new DistributedBarrier(client, "/barriers/import")) {
      // Advisory: true-at-some-recent-revision, and the barrier may lift a
      // microsecond later. Never branch on it to decide whether to wait.
      if (barrier.isBarrierSet()) {
        System.out.println("Barrier is up; blocking");
      }

      // Blocks until the barrier key is deleted.
      barrier.waitOnBarrier();
      System.out.println("Released");
    }
    // --8<-- [end:wait]
  }

  public void waitTimeout(Client client) throws InterruptedException {
    // --8<-- [start:wait-timeout]
    try (DistributedBarrier barrier = new DistributedBarrier(client, "/barriers/import")) {
      // Java uses the (long, TimeUnit) overload; the Duration one is Kotlin-facing.
      // false means the timeout elapsed with the barrier still up. Waiting again is
      // fine — a waiter holds no state between calls.
      if (barrier.waitOnBarrier(30, TimeUnit.SECONDS)) {
        System.out.println("Released within the timeout");
      } else {
        System.out.println("Still blocked after 30s");
      }
    }
    // --8<-- [end:wait-timeout]
  }

  public void missing(Client client) throws InterruptedException {
    // --8<-- [start:missing]
    // waitOnMissingBarriers=false: an unset barrier is treated as already lifted,
    // so waitOnBarrier() returns true immediately. Java has no named arguments, so
    // every preceding parameter must be supplied positionally.
    try (DistributedBarrier barrier =
           new DistributedBarrier(
             client,
             "/barriers/import",
             5L,        // leaseTtlSecs
             false)) {  // waitOnMissingBarriers
      barrier.waitOnBarrier();
    }
    // --8<-- [end:missing]
  }

  public void withCount(Client client) throws InterruptedException, EtcdRecipeException {
    // --8<-- [start:with-count]
    // Every one of the five members runs this. Nobody proceeds until all five are
    // parked here; then all five leave together.
    try (DistributedBarrierWithCount barrier =
           new DistributedBarrierWithCount(client, "/barriers/phase1", 5)) {
      System.out.println(barrier.getWaiterCount() + " of " + barrier.getMemberCount() + " waiting");
      barrier.waitOnBarrier();
      System.out.println("All " + barrier.getMemberCount() + " arrived");
    }
    // --8<-- [end:with-count]
  }

  public void withCountTimeout(Client client) throws InterruptedException, EtcdRecipeException {
    // --8<-- [start:with-count-timeout]
    try (DistributedBarrierWithCount barrier =
           new DistributedBarrierWithCount(client, "/barriers/phase1", 5)) {
      // A timeout drops this member out of the count until it waits again, so a
      // straggler cannot be counted twice.
      if (!barrier.waitOnBarrier(30, TimeUnit.SECONDS)) {
        System.out.println("Only " + barrier.getWaiterCount() + " of "
          + barrier.getMemberCount() + " showed up");
      }
    }
    // --8<-- [end:with-count-timeout]
  }

  public void doubleBarrier(Client client) throws InterruptedException, EtcdRecipeException {
    // --8<-- [start:double]
    // Two DistributedBarrierWithCount instances under the covers, at
    // /barriers/phase1/enter and /barriers/phase1/leave.
    try (DistributedDoubleBarrier barrier =
           new DistributedDoubleBarrier(client, "/barriers/phase1", 5)) {
      System.out.println(barrier.getEnterWaiterCount() + " peers already at the gate");

      // Nobody starts the phase until all five have arrived.
      barrier.enter();

      // ... run the phase ...

      // Nobody tears down until all five have finished it.
      barrier.leave();
      System.out.println(barrier.getLeaveWaiterCount() + " peers still leaving");
    }
    // --8<-- [end:double]
  }

  public void doubleBarrierTimeout(Client client) throws InterruptedException, EtcdRecipeException {
    // --8<-- [start:double-timeout]
    try (DistributedDoubleBarrier barrier =
           new DistributedDoubleBarrier(client, "/barriers/phase1", 5)) {
      if (barrier.enter(30, TimeUnit.SECONDS)) {
        // Bound the leave too: a peer that dies mid-phase must not park the rest
        // of the cluster forever.
        if (!barrier.leave(30, TimeUnit.SECONDS)) {
          System.out.println("Only " + barrier.getLeaveWaiterCount() + " left cleanly");
        }
      } else {
        System.out.println("Only " + barrier.getEnterWaiterCount() + " entered");
      }
    }
    // --8<-- [end:double-timeout]
  }
}
