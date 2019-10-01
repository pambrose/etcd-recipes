package org.athenian.election;

import static org.athenian.utils.Utils.random;
import static org.athenian.utils.Utils.sleep;

public class SingleLeaderElectionDemo {

    public static void main(String[] args) {

        String url = "http://localhost:2379";
        String electionName = "/election/leaderElectionDemo";


        try (LeaderElection election = new LeaderElection(url, electionName)) {

            election.start(new ElectionActions(
                    () -> {
                        System.out.println(election.getId() + " initialized");
                        return null;
                    },
                    () -> {
                        System.out.println(election.getId() + " elected leader");
                        long pause = random(5);
                        sleep(pause);
                        System.out.println(election.getId() + " surrendering after " + pause + " seconds");

                        return null;
                    },
                    () -> {
                        return null;
                    },
                    () -> {
                        return null;
                    }));

            election.await();

        }
    }
}
