package org.athenian.utils;

import java.util.Random;

public class Utils {

    private static Random random = new Random();

    public static long random(long upper) {
        return Math.abs(random.nextLong() % upper);
    }

    public static void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
