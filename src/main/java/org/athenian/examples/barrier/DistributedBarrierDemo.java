package org.athenian.examples.barrier;

import org.athenian.barrier.DistributedBarrier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DistributedBarrierDemo {

    public static void main(String[] args) throws InterruptedException {
        String url = "http://localhost:2379";
        String barrierName = "/barriers/threadedclients";
        int threadCount = 5;
        CountDownLatch waitLatch = new CountDownLatch(threadCount);
        CountDownLatch goLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);

        DistributedBarrier.Companion.reset(url, barrierName);

        executor.execute(() -> {
            try (DistributedBarrier barrier = new DistributedBarrier(url, barrierName, true)) {
                System.out.println("Setting Barrier");
                barrier.setBarrier();

                goLatch.countDown();
                Thread.sleep(6_000);

                System.out.println("Removing Barrier");
                barrier.removeBarrier();
                Thread.sleep(3_000);

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            executor.execute(() -> {
                        try {
                            goLatch.await();
                            try (DistributedBarrier barrier = new DistributedBarrier(url, barrierName, true)) {
                                System.out.println(String.format("%d Waiting on Barrier", id));
                                barrier.waitOnBarrier(1, TimeUnit.SECONDS);

                                System.out.println(String.format("%d Timedout waiting on barrier, waiting again", id));
                                barrier.waitOnBarrier();

                                System.out.println(String.format("%d Done Waiting on Barrier", id));
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
        System.out.println("Done");

    }
}
