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

package io.etcd.recipes.examples.discovery;

import io.etcd.recipes.common.EtcdRecipeException;
import io.etcd.recipes.discovery.ServiceCache;
import io.etcd.recipes.discovery.ServiceDiscovery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.sudothought.common.util.Misc.sleepSecs;
import static java.lang.String.format;

public class ServiceCacheExample {

    public static void main(String[] args) throws EtcdRecipeException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            try (ServiceDiscovery sd = new ServiceDiscovery(ServiceDiscoveryExample.urls, ServiceDiscoveryExample.path)) {
                try (ServiceCache cache = sd.serviceCache(ServiceDiscoveryExample.serviceName)) {
                    cache.addListenerForChanges(
                            (eventType, isAdd, name, serviceInstance) -> {
                                String action = isAdd ? "added" : "updated";
                                System.out.println(format("Change %s %s %s", eventType, action, name));
                                if (serviceInstance != null)
                                    System.out.println("Payload = " + IntPayload.toObject(serviceInstance.getJsonPayload()));
                                //System.out.println(cache.getInstances());
                            }
                    );

                    cache.start();

                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        ServiceDiscoveryExample.serviceExample(false);
        latch.countDown();
        sleepSecs(1);
        executor.shutdown();
    }
}