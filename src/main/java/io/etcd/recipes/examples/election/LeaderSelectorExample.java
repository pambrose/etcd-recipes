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

package io.etcd.recipes.examples.election;

import com.google.common.collect.Lists;
import io.etcd.jetcd.Client;
import io.etcd.recipes.election.LeaderSelector;
import io.etcd.recipes.election.LeaderSelectorListener;

import java.util.List;

import static com.sudothought.common.util.Misc.random;
import static com.sudothought.common.util.Misc.sleepSecs;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static java.lang.String.format;

public class LeaderSelectorExample {

    public static void main(String[] args) throws InterruptedException {
        List<String> urls = Lists.newArrayList("http://localhost:2379");
        String electionPath = "/election/LeaderSelectorExample";
        int count = 5;

        LeaderSelectorListener listener =
                new LeaderSelectorListener() {
                    @Override
                    public void takeLeadership(LeaderSelector selector) {
                        System.out.println(selector.getClientId() + " elected leader");
                        long pause = random(5);
                        sleepSecs(pause);
                        System.out.println(format("%s surrendering after %s seconds", selector.getClientId(), pause));
                    }

                    @Override
                    public void relinquishLeadership(LeaderSelector selector) {
                        System.out.println(format("%s relinquished leadership", selector.getClientId()));
                    }
                };

        try (Client client = connectToEtcd(urls)) {
            System.out.println("Single leader is created and repeatedly runs for election");
            try (LeaderSelector selector = new LeaderSelector(client, electionPath, listener)) {
                for (int i = 0; i < count; i++) {
                    selector.start();

                    selector.waitOnLeadershipComplete();
                }
            }

            System.out.println("\nMultiple leaders are created and each runs for election once");
            List<LeaderSelector> selectors = Lists.newArrayList();
            for (int i = 0; i < count; i++)
                selectors.add(new LeaderSelector(client, electionPath, listener));

            for (LeaderSelector selector : selectors)
                selector.start();

            System.out.println(format("Participants: %s", LeaderSelector.getParticipants(client, electionPath)));

            for (LeaderSelector selector : selectors)
                selector.waitOnLeadershipComplete();

            for (LeaderSelector selector : selectors)
                selector.close();
        }
    }
}
