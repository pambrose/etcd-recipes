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

package com.sudothought.etcdrecipes.examples.counter;


import com.sudothought.etcdrecipes.counter.DistributedAtomicLong;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.sudothought.common.util.Misc.random;
import static com.sudothought.common.util.Misc.sleepMillis;

public class DistributedAtomicLongExample {

    public static void main(String[] args) throws InterruptedException {
        String url = "http://localhost:2379";
        String counterPath = "/counter/counterdemo";
        int counterCount = 10;
        CountDownLatch outerLatch = new CountDownLatch(counterCount);
        ExecutorService executor = Executors.newCachedThreadPool();

        DistributedAtomicLong.Static.reset(url, counterPath);

        for (int i = 0; i < counterCount; i++) {
            final int id = i;
            executor.submit(() -> {
                try (DistributedAtomicLong counter = new DistributedAtomicLong(url, counterPath)) {
                    System.out.println("Creating counter #" + id);
                    CountDownLatch innerLatch = new CountDownLatch(4);
                    int count = 50;
                    int pause = 50;

                    executor.submit(() -> {
                        System.out.println("Begin increments for counter #" + id);
                        for (int j = 0; j < count; j++) counter.increment();
                        sleepMillis(random(pause));
                        innerLatch.countDown();
                        System.out.println("Completed increments for counter #" + id);
                    });

                    executor.submit(() -> {
                        System.out.println("Begin decrements for counter #" + id);
                        for (int j = 0; j < count; j++) counter.decrement();
                        sleepMillis(random(pause));
                        innerLatch.countDown();
                        System.out.println("Completed decrements for counter #" + id);
                    });

                    executor.submit(() -> {
                        System.out.println("Begin adds for counter #" + id);
                        for (int j = 0; j < count; j++) counter.add(5);
                        sleepMillis(random(pause));
                        innerLatch.countDown();
                        System.out.println("Completed adds for counter #" + id);
                    });

                    executor.submit(() -> {
                        System.out.println("Begin subtracts for counter #" + id);
                        for (int j = 0; j < count; j++) counter.subtract(5);
                        sleepMillis(random(pause));
                        innerLatch.countDown();
                        System.out.println("Completed subtracts for counter #" + id);
                    });

                    try {
                        innerLatch.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                outerLatch.countDown();
            });
        }

        outerLatch.await();

        executor.shutdown();

        try (DistributedAtomicLong counter = new DistributedAtomicLong(url, counterPath)) {
            System.out.println(String.format("Counter value = %d", counter.get()));
        }
    }
}
