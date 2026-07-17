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
import io.etcd.recipes.lock.DistributedSemaphore;

import java.util.concurrent.TimeUnit;

public class SemaphoreSnippets {

  public void basic(Client client) throws InterruptedException {
    // --8<-- [start:basic]
    // At most three clients cluster-wide run the guarded section at once.
    try (DistributedSemaphore semaphore = new DistributedSemaphore(client, "/semaphores/api-quota", 3)) {
      semaphore.acquire();
      try {
        System.out.println("Calling the rate-limited API");
      } finally {
        semaphore.release();
      }
    }
    // --8<-- [end:basic]
  }

  public void tryAcquire(Client client) throws InterruptedException {
    // --8<-- [start:try-acquire]
    try (DistributedSemaphore semaphore = new DistributedSemaphore(client, "/semaphores/api-quota", 3)) {
      if (semaphore.tryAcquire(2, TimeUnit.SECONDS)) {
        try {
          System.out.println("Got a permit; " + semaphore.availablePermits() + " left (advisory)");
        } finally {
          semaphore.release();
        }
      } else {
        System.out.println("All permits taken; shedding load");
      }
    }
    // --8<-- [end:try-acquire]
  }
}
