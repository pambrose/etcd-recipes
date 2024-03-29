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
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch.Watcher;
import io.etcd.recipes.common.KVUtils;
import kotlin.Unit;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.pambrose.common.util.MiscJavaFuncs.sleepSecs;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsString;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.putValue;
import static io.etcd.recipes.common.WatchUtils.watcher;

public class SetValueAndWatch {

    public static void main(String[] args) throws InterruptedException {
        List<String> urls = Lists.newArrayList("http://localhost:2379");
        String path = "/foo";
        String keyval = "foobar";
        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch latch = new CountDownLatch(2);

        executor.submit(() -> {
            try (Client client = connectToEtcd(urls)) {
                for (int i = 0; i < 10; i++) {
                    String kv = keyval + i;
                    System.out.printf("Assigning %s = %s%n", path, kv);
                    putValue(client, path, kv);
                    sleepSecs(2);
                    System.out.printf("Deleting %s%n", path);
                    KVUtils.deleteKey(client, path);
                    sleepSecs(1);
                }
            }
            finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try (Client client = connectToEtcd(urls);
                 Watcher watcher =
                         watcher(client,
                                 path,
                                 (watchResponse) -> {
                                     watchResponse.getEvents().forEach((event) -> {
                                         KeyValue keyValue = event.getKeyValue();
                                         System.out.printf("Watch event: %s %s %s%n",
                                                           event.getEventType(),
                                                           getAsString(keyValue.getKey()),
                                                           getAsString(keyValue.getValue()));
                                     });
                                     return Unit.INSTANCE;
                                 })) {
                {
                    System.out.println("Started watch");
                    sleepSecs(10);
                    System.out.println("Closing watch");
                }
                System.out.println("Closed watch");
            }
            finally {
                latch.countDown();
            }
        });

        latch.await();
        executor.shutdown();
    }
}
