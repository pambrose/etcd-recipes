/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.athenian.examples.discovery;

import org.athenian.discovery.IntPayload;
import org.athenian.discovery.ServiceCache;
import org.athenian.discovery.ServiceDiscovery;

import static com.sudothought.common.util.Misc.sleepSecs;

public class ServiceCacheDemo {

    public static void main(String[] args) {
        String url = "http://localhost:2379";
        String serviceName = "/services/test";

        try (ServiceDiscovery sd = new ServiceDiscovery(url, serviceName)) {

            sd.start();

            try (ServiceCache cache = sd.serviceCache("TestName")) {
                cache.addListenerForChanges(
                        (eventType, name, serviceInstance) -> {
                            System.out.println(String.format("Change %s %s %s", eventType, name, serviceInstance));
                            if (serviceInstance != null)
                                System.out.println("Payload: " + IntPayload.Companion.toObject(serviceInstance.getJsonPayload()));
                            System.out.println(cache.getInstances());
                        }
                );

                cache.start();

                sleepSecs(Long.MAX_VALUE);
            }
        }
    }
}