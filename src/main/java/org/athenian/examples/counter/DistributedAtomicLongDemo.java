package org.athenian.examples.counter;

import org.athenian.counter.DistributedAtomicLong;
import org.athenian.utils.Utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DistributedAtomicLongDemo {

    public static void main(String[] args) throws InterruptedException {
        String url = "http://localhost:2379";
        String counterName = "/counter/counterdemo";
        int counterCount = 10;
        CountDownLatch outerLatch = new CountDownLatch(counterCount);
        ExecutorService executor = Executors.newCachedThreadPool();

        DistributedAtomicLong.Static.reset(url, counterName);

        for (int i = 0; i < counterCount; i++) {
            final int id = i;
            executor.submit(() -> {
                try (DistributedAtomicLong counter = new DistributedAtomicLong(url, counterName)) {
                    System.out.println("Creating counter #" + id);
                    CountDownLatch innerLatch = new CountDownLatch(4);
                    int count = 50;
                    int maxPause = 50;

                    executor.submit(() -> {
                        System.out.println("Begin increments for counter #" + id);
                        for (int j = 0; j < count; j++) counter.increment();
                        Utils.sleep(Utils.random(maxPause));
                        innerLatch.countDown();
                        System.out.println("Completed increments for counter #" + id);
                    });

                    executor.submit(() -> {
                        System.out.println("Begin decrements for counter #" + id);
                        for (int j = 0; j < count; j++) counter.decrement();
                        Utils.sleep(Utils.random(maxPause));
                        innerLatch.countDown();
                        System.out.println("Completed decrements for counter #" + id);
                    });

                    executor.submit(() -> {
                        System.out.println("Begin adds for counter #" + id);
                        for (int j = 0; j < count; j++) counter.add(5);
                        Utils.sleep(Utils.random(maxPause));
                        innerLatch.countDown();
                        System.out.println("Completed adds for counter #" + id);
                    });

                    executor.submit(() -> {
                        System.out.println("Begin subtracts for counter #" + id);
                        for (int j = 0; j < count; j++) counter.subtract(5);
                        Utils.sleep(Utils.random(maxPause));
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

        try (DistributedAtomicLong counter = new DistributedAtomicLong(url, counterName)) {
            System.out.println(String.format("Counter value = %d", counter.get()));
        }
    }
}
