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

package io.etcd.recipes.examples.barrier;

import com.google.common.collect.Lists;
import io.etcd.jetcd.Client;
import io.etcd.recipes.barrier.DistributedBarrier;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.pambrose.common.util.JavaFuncs.sleepSecs;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static java.lang.String.format;

public class DistributedBarrierExample {

    public static void main(String[] args) throws InterruptedException {
        List<String> urls = Lists.newArrayList("http://localhost:2379");
        String path = "/barriers/DistributedBarrierExample";
        int threadCount = 5;
        CountDownLatch waitLatch = new CountDownLatch(threadCount);
        CountDownLatch goLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newCachedThreadPool();

        executor.submit(() -> {
            try (Client client = connectToEtcd(urls);
                 DistributedBarrier barrier = new DistributedBarrier(client, path)) {
                System.out.println("Setting Barrier");
                barrier.setBarrier();

                goLatch.countDown();
                sleepSecs(6);

                System.out.println("Removing Barrier");
                barrier.removeBarrier();
                sleepSecs(3);
            }
        });

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            executor.submit(() -> {
                        try {
                            goLatch.await();
                            try (Client client = connectToEtcd(urls);
                                 DistributedBarrier barrier = new DistributedBarrier(client, path)) {
                                System.out.println(format("%d Waiting on Barrier", id));
                                barrier.waitOnBarrier(1, TimeUnit.SECONDS);

                                System.out.println(format("%d Timed out waiting on barrier, waiting again", id));
                                barrier.waitOnBarrier();

                                System.out.println(format("%d Done Waiting on Barrier", id));
                                waitLatch.countDown();
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
            );
        }

        waitLatch.await();
        executor.shutdown();
    }
}