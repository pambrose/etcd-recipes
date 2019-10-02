package org.athenian.examples.election;

import org.athenian.election.LeaderSelector;
import org.athenian.election.LeaderSelectorListener;

import static org.athenian.utils.Utils.random;
import static org.athenian.utils.Utils.sleep;

public class SingleLeaderElectionDemo {

    public static void main(String[] args) throws InterruptedException {

        String url = "http://localhost:2379";
        String electionName = "/election/leaderElectionDemo";

        LeaderSelector.Static.reset(url, electionName);

        LeaderSelectorListener listener =
                election -> {
                    System.out.println(election.getId() + " elected leader");
                    long pause = random(5);
                    sleep(pause);
                    System.out.println(election.getId() + " surrendering after " + pause + " seconds");

                };

        try (LeaderSelector election = new LeaderSelector(url, electionName, listener)) {
            for (int i = 0; i < 5; i++) {
                election.start();
                election.await();
            }
        }

        for (int i = 0; i < 5; i++) {
            try (LeaderSelector election = new LeaderSelector(url, electionName, listener)) {
                election.start();
                election.await();
            }
        }
    }
}
