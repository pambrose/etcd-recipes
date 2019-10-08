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

package com.sudothought.etcdrecipes.examples.discovery;

import com.google.common.collect.Lists;
import com.sudothought.etcdrecipes.common.EtcdRecipeException;
import com.sudothought.etcdrecipes.discovery.ServiceDiscovery;
import com.sudothought.etcdrecipes.discovery.ServiceInstance;

import java.util.List;

import static com.sudothought.common.util.Misc.sleepSecs;

public class ServiceDiscoveryExample {

    public static void main(String[] args) throws EtcdRecipeException {
        List<String> urls = Lists.newArrayList("http://localhost:2379");
        String servicePath = "/services/test";

        try (ServiceDiscovery sd = new ServiceDiscovery(urls, servicePath)) {

            IntPayload payload = new IntPayload(-999);
            ServiceInstance service = ServiceInstance.newBuilder("TestName", payload.toJson()).build();

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

            try {
                System.out.println("Retrieved value: " + sd.queryForInstance(service.getName(), service.getId()));
            } catch (EtcdRecipeException e) {
                System.out.println("Exception: " + e);
            }

            sleepSecs(2);
        }
    }
}