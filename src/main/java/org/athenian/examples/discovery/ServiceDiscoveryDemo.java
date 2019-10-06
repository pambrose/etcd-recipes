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
import org.athenian.discovery.ServiceDiscovery;
import org.athenian.discovery.ServiceInstance;

import static com.sudothought.common.util.Misc.sleepSecs;

public class ServiceDiscoveryDemo {

    public static void main(String[] args) {
        String url = "http://localhost:2379";
        String serviceName = "/services/test";

        try (ServiceDiscovery sd = new ServiceDiscovery(url, serviceName)) {

            sd.start();

            IntPayload payload = new IntPayload(-999);
            ServiceInstance service = ServiceInstance.Companion.newBuilder("TestName", payload.toJson()).build();

            System.out.println(service.toJson());

            System.out.println("Registering");
            sd.registerService(service);
            System.out.println("Retrieved value: " + sd.queryForInstance(service.getName(), service.getId()));
            System.out.println("Retrieved values: " + sd.queryForInstances(service.getName()));
            System.out.println("Retrieved names: " + sd.queryForNames());

            sleepSecs(2);
            System.out.println("Updating");
            payload.setIntval(-888);
            service.setJsonPayload(payload.toJson());
            sd.updateService(service);
            System.out.println("Retrieved value: " + sd.queryForInstance(service.getName(), service.getId()));
            System.out.println("Retrieved values: " + sd.queryForInstances(service.getName()));
            System.out.println("Retrieved names: " + sd.queryForNames());

            sleepSecs(2);
            System.out.println("Unregistering");
            sd.unregisterService(service);
            sleepSecs(3);

            System.out.println("Retrieved value: " + sd.queryForInstance(service.getName(), service.getId()));

            sleepSecs(2);
        }
    }
}