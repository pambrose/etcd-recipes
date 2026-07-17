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
import io.etcd.recipes.lock.DistributedReadWriteLock;

import java.util.concurrent.TimeUnit;

public class ReadWriteLockSnippets {

  public void basic(Client client) throws InterruptedException {
    // --8<-- [start:basic]
    try (DistributedReadWriteLock rwLock = new DistributedReadWriteLock(client, "/locks/catalog")) {
      // Any number of readers may hold the read lock at once...
      rwLock.getReadLock().lock();
      try {
        System.out.println("Reading the catalog");
      } finally {
        rwLock.getReadLock().unlock();
      }

      // ...but a writer excludes every reader and every other writer.
      rwLock.getWriteLock().lock();
      try {
        System.out.println("Rewriting the catalog");
      } finally {
        rwLock.getWriteLock().unlock();
      }
    }
    // --8<-- [end:basic]
  }

  public void tryLock(Client client) throws InterruptedException {
    // --8<-- [start:try-lock]
    try (DistributedReadWriteLock rwLock = new DistributedReadWriteLock(client, "/locks/catalog")) {
      if (rwLock.getWriteLock().tryLock(5, TimeUnit.SECONDS)) {
        try {
          System.out.println("Got the write lock");
        } finally {
          rwLock.getWriteLock().unlock();
        }
      }
    }
    // --8<-- [end:try-lock]
  }
}
