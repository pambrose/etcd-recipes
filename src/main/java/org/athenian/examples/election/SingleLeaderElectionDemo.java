package org.athenian.examples.election;

import org.athenian.election.ElectionActions;
import org.athenian.election.LeaderElection;

import static org.athenian.utils.Utils.random;
import static org.athenian.utils.Utils.sleep;

public class SingleLeaderElectionDemo {

    public static void main(String[] args) {

        String url = "http://localhost:2379";
        String electionName = "/election/leaderElectionDemo";

        ElectionActions actions =
                new ElectionActions(
                        (election) -> {
                            System.out.println(election.getId() + " elected leader");
                            long pause = random(5);
                            sleep(pause);
                            System.out.println(election.getId() + " surrendering after " + pause + " seconds");
                            return null;
                        });

        try (LeaderElection election = new LeaderElection(url, electionName, actions)) {
            for (int i = 0; i < 5; i++) {
                election.start();
                election.await();
            }
        }

        for (int i = 0; i < 5; i++) {
            try (LeaderElection election = new LeaderElection(url, electionName, actions)) {
                election.start();
                election.await();
            }
        }
    }
}
