package org.athenian.examples.election;

import org.athenian.election.LeaderSelector;
import org.athenian.election.LeaderSelectorListener;

import static org.athenian.utils.Utils.random;
import static org.athenian.utils.Utils.sleepSecs;

public class SingleLeaderElectionDemo {

    public static void main(String[] args) throws InterruptedException {

        String url = "http://localhost:2379";
        String electionName = "/election/leaderElectionDemo";

        LeaderSelector.Static.reset(url, electionName);

        LeaderSelectorListener listener =
                selector -> {
                    System.out.println(selector.getId() + " elected leader");
                    long pause = random(5);
                    sleepSecs(pause);
                    System.out.println(selector.getId() + " surrendering after " + pause + " seconds");

                };

        try (LeaderSelector selector = new LeaderSelector(url, electionName, listener)) {
            for (int i = 0; i < 5; i++) {
                selector.start();

                while (!selector.isFinished()) {
                    System.out.println(LeaderSelector.Static.getParticipants(url, electionName));
                    sleepSecs(1);
                }

                selector.await();
            }
        }

        for (int i = 0; i < 5; i++) {
            try (LeaderSelector selector = new LeaderSelector(url, electionName, listener)) {
                selector.start();

                while (!selector.isFinished()) {
                    System.out.println(LeaderSelector.Static.getParticipants(url, electionName));
                    sleepSecs(1);
                }

                selector.await();
            }
        }
    }
}
