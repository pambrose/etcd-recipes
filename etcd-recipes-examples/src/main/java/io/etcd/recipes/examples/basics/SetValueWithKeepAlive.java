/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.etcd.recipes.examples.basics;

import com.google.common.collect.Lists;
import io.etcd.jetcd.Client;
import kotlin.Unit;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.pambrose.common.util.MiscJavaFuncs.sleepSecs;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KeepAliveUtils.putValueWithKeepAlive;
import static io.etcd.recipes.common.WatchUtils.getKeyAsString;
import static io.etcd.recipes.common.WatchUtils.watcherWithLatch;
import static java.lang.String.format;

public class SetValueWithKeepAlive {

  public static void main(String[] args) throws InterruptedException {
    List<String> urls = Lists.newArrayList("http://localhost:2379");
    String path = "/foo";
    String keyval = "foobar";
    ExecutorService executor = Executors.newCachedThreadPool();
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch endWatchLatch = new CountDownLatch(1);

    executor.submit(() -> {
      try (Client client = connectToEtcd(urls)) {
        watcherWithLatch(client,
            path,
            endWatchLatch,
            (event) -> {
              System.out.println(format("Updated key: %s", getKeyAsString(event)));
              return Unit.INSTANCE;
            },
            (event) -> {
              System.out.println(format("Deleted key: %s", getKeyAsString(event)));
              return Unit.INSTANCE;
            }
        );
      }
    });

    executor.submit(() -> {
      try (Client client = connectToEtcd(urls)) {
        System.out.println(format("Assigning %s = %s", path, keyval));
        putValueWithKeepAlive(client, path, keyval, 2,
            () -> {
              System.out.println("Starting sleep");
              sleepSecs(5);
              System.out.println("Finished sleep");
              return Unit.INSTANCE;
            });
        System.out.println("Keep-alive is now terminated");
        sleepSecs(5);
      } finally {
        System.out.println("Releasing latch");
        latch.countDown();
      }
    });

    latch.await();
    System.out.println("Releasing endWatchLatch");
    endWatchLatch.countDown();

    executor.shutdown();
  }
}
