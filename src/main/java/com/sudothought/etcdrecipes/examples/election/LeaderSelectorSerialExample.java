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

package com.sudothought.etcdrecipes.examples.election;

import com.sudothought.etcdrecipes.election.LeaderSelector;
import com.sudothought.etcdrecipes.election.LeaderSelectorListener;
import org.jetbrains.annotations.NotNull;

import static com.sudothought.common.util.Misc.random;
import static com.sudothought.common.util.Misc.sleepSecs;

public class LeaderSelectorSerialExample {

    public static void main(String[] args) throws InterruptedException {
        String url = "http://localhost:2379";
        String electionPath = "/election/javademo";

        LeaderSelector.Static.reset(url, electionPath);

        LeaderSelectorListener listener = new LeaderSelectorListener() {
            @Override
            public void takeLeadership(@NotNull LeaderSelector selector) {
                System.out.println(selector.getClientId() + " elected leader");
                long pause = random(5);
                sleepSecs(pause);
                System.out.println(selector.getClientId() + " surrendering after " + pause + " seconds");
            }

            @Override
            public void relinquishLeadership(@NotNull LeaderSelector selector) {
                System.out.println(selector.getClientId() + " relinquished leadership");
            }
        };

        try (LeaderSelector selector = new LeaderSelector(url, electionPath, listener)) {
            for (int i = 0; i < 5; i++) {
                selector.start();

                while (!selector.isFinished()) {
                    System.out.println(LeaderSelector.Static.getParticipants(url, electionPath));
                    sleepSecs(1);
                }

                selector.waitOnLeadershipComplete();
            }
        }

        for (int i = 0; i < 5; i++) {
            try (LeaderSelector selector = new LeaderSelector(url, electionPath, listener)) {
                selector.start();

                while (!selector.isFinished()) {
                    System.out.println(LeaderSelector.Static.getParticipants(url, electionPath));
                    sleepSecs(1);
                }

                selector.waitOnLeadershipComplete();
            }
        }
    }
}
