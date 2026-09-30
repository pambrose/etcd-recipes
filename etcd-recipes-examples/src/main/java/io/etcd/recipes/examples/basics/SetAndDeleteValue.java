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

package io.etcd.recipes.examples.basics;

import io.etcd.jetcd.Client;
import io.etcd.recipes.common.KVUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.getValue;
import static io.etcd.recipes.common.KVUtils.putValue;

public class SetAndDeleteValue {
  public static void main(String[] args) throws InterruptedException {
    List<String> urls = List.of("http://localhost:2379");
    String path = "/foo";
    String keyval = "foobar";
    ExecutorService executor = Executors.newCachedThreadPool();
    CountDownLatch latch = new CountDownLatch(2);

    executor.submit(() -> {
      try {
        TimeUnit.SECONDS.sleep(3);

        try (Client client = connectToEtcd(urls)) {
          System.out.printf("Assigning %s = %s%n", path, keyval);
          putValue(client, path, keyval);
          TimeUnit.SECONDS.sleep(5);
          System.out.printf("Deleting %s%n", path);
          KVUtils.deleteKey(client, path);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        latch.countDown();
      }
    });

    executor.submit(() -> {
      try (Client client = connectToEtcd(urls)) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
          long elapsed = System.currentTimeMillis() - start;
          System.out.printf("Key %s = %s after %dms%n", path, getValue(client, path, "unset"), elapsed);
          TimeUnit.SECONDS.sleep(1);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        latch.countDown();
      }
    });

    latch.await();
    executor.shutdown();
  }
}
