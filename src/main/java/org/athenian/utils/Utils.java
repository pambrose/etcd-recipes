package org.athenian.utils;

import java.util.Random;

public class Utils {

    private static final Random random = new Random();

    public static long random(long upper) {
        return Math.abs(random.nextLong() % upper);
    }

    public static void sleepMillis(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void sleepSecs(long time) {
        sleepMillis(time * 1000);
    }
}
