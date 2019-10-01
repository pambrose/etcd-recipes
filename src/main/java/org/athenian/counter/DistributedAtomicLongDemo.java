package org.athenian.counter;

import java.util.Random;
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
        Random random = new Random();

        DistributedAtomicLong.Static.reset(url, counterName);

        for (int i = 0; i < counterCount; i++) {
            executor.submit(() -> {
                try (DistributedAtomicLong counter = new DistributedAtomicLong(url, counterName)) {
                    CountDownLatch innerLatch = new CountDownLatch(4);
                    int count = 50;
                    int maxPause = 50;

                    executor.submit(() -> {
                        for (int j = 0; j < count; j++) counter.increment();
                        try {
                            Thread.sleep(Math.abs(random.nextLong() % maxPause));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        innerLatch.countDown();
                    });

                    executor.submit(() -> {
                        for (int j = 0; j < count; j++) counter.decrement();
                        try {
                            Thread.sleep(Math.abs(random.nextLong() % maxPause));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        innerLatch.countDown();
                    });

                    executor.submit(() -> {
                        for (int j = 0; j < count; j++) counter.add(5);
                        try {
                            Thread.sleep(Math.abs(random.nextLong() % maxPause));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        innerLatch.countDown();
                    });

                    executor.submit(() -> {
                        for (int j = 0; j < count; j++) counter.subtract(5);
                        try {
                            Thread.sleep(Math.abs(random.nextLong() % maxPause));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        innerLatch.countDown();
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
            System.out.println(String.format("Total: %d", counter.get()));
        }
    }
}
