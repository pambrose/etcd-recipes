/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

import com.google.common.collect.Lists;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.options.PutOption;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.pambrose.common.util.MiscJavaFuncs.sleepSecs;
import static io.etcd.recipes.common.BuilderUtils.putOption;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.getValue;
import static io.etcd.recipes.common.KVUtils.putValue;

public class SetValueWithLease {

  public static void main(String[] args) throws InterruptedException {
    List<String> urls = Lists.newArrayList("http://localhost:2379");
    String path = "/foo";
    String keyval = "foobar";
    ExecutorService executor = Executors.newCachedThreadPool();
    CountDownLatch latch = new CountDownLatch(1);

    executor.submit(() -> {
      try (Client client = connectToEtcd(urls)) {
        Lease leaseClient = client.getLeaseClient();
        System.out.printf("Assigning %s = %s%n", path, keyval);
        LeaseGrantResponse lease = leaseClient.grant(5).get();
        PutOption putOption = putOption((PutOption.Builder builder) -> builder.withLeaseId(lease.getID()));
        putValue(client, path, keyval, putOption);
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      } finally {
        latch.countDown();
      }
    });


    executor.submit(() -> {
      try (Client client = connectToEtcd(urls)) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
          String kval = getValue(client, path, "unset");
          System.out.printf("Key %s = %s after %sms%n", path, kval, System.currentTimeMillis() - start);
          sleepSecs(1);
        }
      }
    });

    latch.await();
    executor.shutdown();
  }
}
