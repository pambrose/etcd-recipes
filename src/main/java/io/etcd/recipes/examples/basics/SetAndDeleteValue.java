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
import io.etcd.jetcd.KV;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.sudothought.common.util.Misc.sleepMillis;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.*;
import static java.lang.String.format;

public class SetAndDeleteValue {
    public static void main(String[] args) throws InterruptedException {
        List<String> urls = Lists.newArrayList("http://localhost:2379");
        String path = "/foo";
        String keyval = "foobar";
        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch latch = new CountDownLatch(2);

        executor.execute(() -> {
            sleepMillis(3_000);

            try (Client client = connectToEtcd(urls);
                 KV kvClient = client.getKVClient()) {
                System.out.println(format("Assigning %s = %s", path, keyval));
                putValue(kvClient, path, keyval);

                sleepMillis(5_000);

                System.out.println(format("Deleting %s", path));
                delete(kvClient, path);
            }
            latch.countDown();
        });

        executor.execute(() -> {
            try (Client client = connectToEtcd(urls);
                 KV kvClient = client.getKVClient()) {
                long start = System.currentTimeMillis();
                for (int i = 0; i < 12; i++) {
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println(format("Key %s = %s after %dms",
                            path, getValue(kvClient, path, "unset"), elapsed));
                    sleepMillis(1_000);
                }
            }
            latch.countDown();
        });

        latch.await();
        executor.shutdown();
    }
}