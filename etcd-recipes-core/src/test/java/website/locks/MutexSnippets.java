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

package website.locks;

import io.etcd.jetcd.Client;
import io.etcd.recipes.lock.DistributedMutex;

import java.util.concurrent.TimeUnit;

public class MutexSnippets {

  public void basic(Client client) throws InterruptedException {
    // --8<-- [start:basic]
    try (DistributedMutex mutex = new DistributedMutex(client, "/locks/orders")) {
      mutex.lock();
      try {
        // Only one client across the cluster runs this at a time.
        System.out.println("Critical section");
      } finally {
        mutex.unlock();
      }
    }
    // --8<-- [end:basic]
  }

  public void tryLock(Client client) throws InterruptedException {
    // --8<-- [start:try-lock]
    try (DistributedMutex mutex = new DistributedMutex(client, "/locks/orders")) {
      // Java uses the (long, TimeUnit) overload; the Duration one is Kotlin-facing.
      if (mutex.tryLock(5, TimeUnit.SECONDS)) {
        try {
          System.out.println("Acquired within the timeout");
        } finally {
          mutex.unlock();
        }
      } else {
        System.out.println("Someone else holds the lock; moving on");
      }
    }
    // --8<-- [end:try-lock]
  }

  public void lockLost(Client client) throws InterruptedException {
    // --8<-- [start:lock-lost]
    try (DistributedMutex mutex = new DistributedMutex(client, "/locks/orders")) {
      // The acquisition lease is deliberately never healed. If it expires, etcd has
      // already promoted the next waiter, so the hold is gone and cannot be reclaimed.
      mutex.addLockLostListener(cause ->
        System.out.println("Lost the lock, abandoning work: " + cause));

      mutex.lock();
      try {
        System.out.println("Working");
      } finally {
        // Returns false if the hold was lost while inside the section.
        mutex.unlock();
      }
    }
    // --8<-- [end:lock-lost]
  }

  public void interruptOnLoss(Client client) throws InterruptedException {
    // --8<-- [start:interrupt-on-loss]
    // Opt in to having the holding thread interrupted the moment the lock is lost,
    // rather than letting it run on against state it no longer owns. Java has no
    // named arguments, so every preceding parameter must be supplied positionally.
    try (DistributedMutex mutex =
           new DistributedMutex(
             client,
             "/locks/orders",
             5L,                                  // leaseTtlSecs
             io.etcd.recipes.common.ResilienceConfig.DEFAULT,
             "worker-1",                          // clientId
             true)) {                             // interruptOnLockLoss
      mutex.lock();
      try {
        System.out.println("Interrupted if the lease expires under us");
      } finally {
        mutex.unlock();
      }
    }
    // --8<-- [end:interrupt-on-loss]
  }
}
