/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.examples.counter;


import com.google.common.collect.Lists;
import io.etcd.jetcd.Client;
import io.etcd.recipes.counter.DistributedAtomicLong;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static java.lang.String.format;

public class DistributedAtomicLongExample {

  public static void main(String[] args) throws InterruptedException {
    List<String> urls = Lists.newArrayList("http://localhost:2379");
    String path = "/counter/counterdemo";
    int threadCount = 10;
    int repeatCount = 25;
    CountDownLatch latch = new CountDownLatch(threadCount);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    try (Client client = connectToEtcd(urls)) {

      DistributedAtomicLong.delete(client, path);

      for (int i = 0; i < threadCount; i++) {
        final int id = i;
        executor.submit(() -> {
          try (DistributedAtomicLong counter = new DistributedAtomicLong(client, path)) {
            System.out.println(format("Creating counter #%d", id));
            for (int j = 0; j < repeatCount; j++) counter.increment();
            for (int j = 0; j < repeatCount; j++) counter.decrement();
            for (int j = 0; j < repeatCount; j++) counter.add(5);
            for (int j = 0; j < repeatCount; j++) counter.subtract(5);
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await();

      try (DistributedAtomicLong counter = new DistributedAtomicLong(client, path)) {
        System.out.println(format("Counter value = %d", counter.get()));
      }
    }

    executor.shutdown();
  }
}
